(ns metrics.callgraph
  "The call graph between a project's functions, from jolt-lsp's index.

  The index is built from the analyses the language adapters made of the files
  the analyzer collected, nothing else: no dependency jars, no files on disk.
  Test files are indexed but left out of the graph, so a function only tests
  call has no callers.  A reference is an edge whether it calls the function or
  passes it on, as in `{:inc c/inc}`, and only edges between the project's own
  vars count.

  Each function, macro and test then gets :fan-in, :fan-out and :reach (see
  metrics.calls), and :referenced?: whether anything outside its own body,
  tests and top-level code included, refers to the var itself.  That is what
  the cleanup candidates go by, since a reference resolved to the var can't be
  a local or another namespace's var of the same name.  Methods, helpers and
  files no adapter indexes get none.

  References from outside the analysis (metrics.references) are indexed too:
  an excluded file's references to the vars count toward :referenced?, though
  never toward the graph, and the ns/name a config names counts as written.
  Their unresolved names don't count, since nothing checks them."
  (:require [clojure.string]
            [jolt-lsp.db :as db]
            [metrics.calls :as calls]
            [metrics.references :as references]))

(defn- index
  "jolt-lsp's index over the files that have an analysis, tests included, and
  the excluded files references holds."
  [files references]
  (db/build-from-analyses
   "."
   (for [f (concat files (:files references))
         :when (:index-analysis f)]
     {:path (:path f) :analysis (:index-analysis f) :external false})))

(defn- analyzed-rows
  "The rows of a table from the analyzed files, not the excluded ones that are
  indexed only for their references."
  [ix references table]
  (let [excluded (set (map :path (:files references)))
        ids (set (keep #(when (contains? excluded (:path %)) (:id %)) (db/all-files ix)))]
    (remove #(contains? ids (:file-id %)) (db/all-rows ix table))))

(defn- in-namespaces?
  "Is fqn a var of one of the namespaces?"
  [namespaces fqn]
  (contains? namespaces (first (clojure.string/split fqn #"/" 2))))

(defn edges
  "[caller callee] for every reference from one of the project's vars to
  another, the vars of test-namespaces and the files of references left out."
  [ix test-namespaces references]
  (let [project-var? (set (remove #(in-namespaces? test-namespaces %)
                                  (keep :fqn (analyzed-rows ix references :var-def))))]
    (vec (distinct
          (for [r (analyzed-rows ix references :var-ref)
                :let [{:keys [caller fqn]} r]
                :when (and caller fqn (not (:refer r))
                           (project-var? caller) (project-var? fqn))]
            [caller fqn])))))

(defn- callers-of
  "{callee #{caller ...}}, built once for every search."
  [edges]
  (reduce (fn [m [caller callee]] (update m callee (fnil conj #{}) caller)) {} edges))

(defn- ancestors-of
  "Every var that reaches start through callers, start left out."
  [callers start]
  (loop [seen #{start}
         frontier [start]]
    (if (empty? frontier)
      (disj seen start)
      (let [found (remove seen (mapcat callers frontier))]
        (recur (into seen found) (vec (distinct found)))))))

(defn- reaches
  "{fqn n}: how many vars reach each var through edges.  Only a var with
  callers can be reached, so the rest are left to default to 0."
  [edges fan-in]
  (let [callers (callers-of edges)]
    (into {} (for [fqn (keys fan-in)]
               [fqn (count (ancestors-of callers fqn))]))))

(defn- referenced
  "Is a var, by fqn and name, used from outside its own body?  Yes when a
  reference resolves to it from another var, a test or top-level code (a :refer
  in an ns form alone isn't a use), when a bare symbol of its name resolves to
  nothing at all (a writ spec's laws call their target's fns so), or when its
  ns/name is written as a symbol, quoted ones included.  A local or another
  var of the same name resolves elsewhere, so it doesn't count.  references
  add the resolved ones from excluded files and the ns/names configs write."
  [ix files references]
  (let [rows (db/all-rows ix :var-ref)
        resolved (set (for [r rows
                            :let [{:keys [caller fqn]} r]
                            :when (and fqn (not (:refer r)) (not= caller fqn))]
                        fqn))
        unresolved (set (keep #(when (:unresolved %) (:name %))
                              (analyzed-rows ix references :var-ref)))
        written (into (set (:written references)) (mapcat :qualified-symbols) files)]
    (fn [fqn nm]
      (boolean (or (contains? resolved fqn) (contains? unresolved nm) (contains? written fqn))))))

(def ^:private graphed-kinds #{:function :macro :test})

(defn- placed
  "A function with its place in the graph, given the counts by fqn."
  [ns-name {:keys [fan-in fan-out reach referenced]} f]
  (if (contains? graphed-kinds (:kind f))
    (let [fqn (str ns-name "/" (:name f))]
      (assoc f
             :fan-in (get fan-in fqn 0)
             :fan-out (get fan-out fqn 0)
             :reach (get reach fqn 0)
             :referenced? (referenced fqn (:name f))))
    f))

(defn- outside-references
  "What refers to the vars from outside the files: the configs under :root,
  less what :ignored? says, and the :excluded source paths."
  [{:keys [root excluded ignored?]}]
  (references/collect (when root (references/config-paths root (or ignored? (constantly false))))
                      excluded))

(defn annotate
  "files with every indexed function's place in the call graph.  outside says
  where to find references from outside the files, as outside-references
  takes it; nil looks nowhere."
  [files outside]
  (if-not (some :index-analysis files)
    files
    (let [references (outside-references outside)
          ix (index files references)
          es (edges ix (set (keep #(when (:test? %) (:namespace %)) files)) references)
          fan-in (calls/fan-in es)
          counts {:fan-in fan-in
                  :fan-out (calls/fan-out es)
                  :reach (reaches es fan-in)
                  :referenced (referenced ix files references)}]
      (mapv (fn [f]
              (if (and (:index-analysis f) (not (:test? f)) (:namespace f))
                (update f :functions #(mapv (partial placed (:namespace f) counts) %))
                f))
            files))))
