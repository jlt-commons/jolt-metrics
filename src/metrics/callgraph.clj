(ns metrics.callgraph
  "The call graph between a project's functions, from jolt-lsp's index.

  The index is built from the analyses the language adapters made of the files
  the analyzer collected, nothing else: no dependency jars, no files on disk.
  Test files are left out, so a function only tests call has no callers.  A
  reference is an edge whether it calls the function or passes it on, as in
  `{:inc c/inc}`, and only edges between the project's own vars count.

  Each function, macro and test then gets :fan-in, :fan-out and :reach (see
  metrics.calls).  Methods, helpers and files no adapter indexes get none."
  (:require [jolt-lsp.db :as db]
            [metrics.calls :as calls]))

(defn- index
  "jolt-lsp's index over the files that have an analysis and aren't tests."
  [files]
  (db/build-from-analyses
   "."
   (for [f files
         :when (and (:index-analysis f) (not (:test? f)))]
     {:path (:path f) :analysis (:index-analysis f) :external false})))

(defn edges
  "[caller callee] for every reference from one of the project's vars to another."
  [ix]
  (let [project-var? (set (keep :fqn (db/all-var-defs ix)))]
    (vec (distinct
          (for [r (db/all-rows ix :var-ref)
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

(def ^:private graphed-kinds #{:function :macro :test})

(defn- placed
  "A function with its place in the graph, given the counts by fqn."
  [ns-name {:keys [fan-in fan-out reach]} f]
  (if (contains? graphed-kinds (:kind f))
    (let [fqn (str ns-name "/" (:name f))]
      (assoc f
             :fan-in (get fan-in fqn 0)
             :fan-out (get fan-out fqn 0)
             :reach (get reach fqn 0)))
    f))

(defn annotate
  "files with every indexed function's place in the call graph."
  [files]
  (if-not (some :index-analysis files)
    files
    (let [es (edges (index files))
          fan-in (calls/fan-in es)
          counts {:fan-in fan-in
                  :fan-out (calls/fan-out es)
                  :reach (reaches es fan-in)}]
      (mapv (fn [f]
              (if (and (:index-analysis f) (not (:test? f)) (:namespace f))
                (update f :functions #(mapv (partial placed (:namespace f) counts) %))
                f))
            files))))
