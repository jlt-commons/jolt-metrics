(ns metrics.core
  "The entry point: turn a project's Clojure/Jolt source into a metrics report.

  analyze-source computes every metric for one file; analyze-files resolves
  architecture coupling across files and rolls the project up."
  (:require [metrics.reader :as reader]
            [metrics.language :as language]
            [metrics.loc :as loc]
            [metrics.paths :as paths]
            [metrics.cyclomatic :as cyclomatic]
            [metrics.cognitive :as cognitive]
            [metrics.halstead :as halstead]
            [metrics.maintainability :as maintainability]
            [metrics.coupling :as coupling]
            [metrics.hotspot :as hotspot]
            [metrics.queue :as queue]
            [metrics.cleanup :as cleanup]
            [metrics.entropy :as entropy]
            [jolt.fs :as fs]))

(defn- average
  "The mean of xs, or nil when there is nothing to average."
  [xs]
  (when (seq xs)
    (/ (reduce + 0 xs) (count xs))))

(defn- average-halstead
  "The mean Halstead metrics of the scopes that hold any code at all; an empty
  function has nothing to measure and must not drag the average down."
  [hs]
  (let [hs (remove #(zero? (:length %)) hs)
        n (count hs)]
    (if (zero? n)
      (halstead/calculate [] [])
      (let [sums (reduce (fn [acc h] (merge-with + acc h))
                         (halstead/calculate [] [])
                         hs)]
        (into {} (map (fn [[k v]] [k (/ v n)]) sums))))))

(defn- helper-lines
  "The lines a definition's named helpers span, which are theirs and not its own.
  Its first line stays its own, whatever else is on it."
  [{:keys [line helpers]} extents]
  (set (for [[l c] helpers
             n (range l (inc (get extents [l c] l)))
             :when (> n line)]
         n)))

(defn- unit-lines
  "A definition's end line and the classes of the lines it spans, leaving out
  those of its named helpers."
  [{:keys [line column] :as f} {:keys [classes extents]}]
  (let [end-line (get extents [line column] line)
        theirs (helper-lines f extents)]
    {:end-line end-line
     :locs (loc/tally (for [n (range (max 1 line) (inc (min end-line (count classes))))
                            :when (not (contains? theirs n))]
                        (nth classes (dec n))))}))

(defn- index-fields
  "The index's terms as result fields, all nil when the index is undefined."
  [mi]
  {:maintainability-index (:maintainability-index mi)
   :maintainability-index-without-comments (:maintainability-index-without-comments mi)
   :comment-weight (:comment-weight mi)})

(defn- scope-index
  "The index of a scope with volume V and complexity G, size lines long, whose
  comment ratio comes from locs."
  [V G lines locs]
  (maintainability/maintainability-index
   V G lines (maintainability/comment-weight (:loc locs) (:cloc locs))))

(defn- function-scores
  "A definition's complexity and size scores."
  [f h cc locs]
  {:cyclomatic cc
   :cognitive (cognitive/complexity (:flow f))
   :nesting (cognitive/max-nesting (:flow f))
   :halstead h
   :halstead-volume (:volume h)
   :locs locs})

(defn- function-metrics
  "Every metric of one definition.  The index uses the definition's own lines, so
  comments elsewhere in the file cannot change it."
  [f scanned]
  (let [h (halstead/calculate (:operators f) (:operands f))
        cc (cyclomatic/complexity (:decision-kinds f))
        {:keys [end-line locs]} (unit-lines f scanned)]
    (merge
     (select-keys f [:kind :line :lloc :params :self-refs])
     {:name (str (:name f))
      :end-line end-line
      :private (boolean (:private f))}
     (function-scores f h cc locs)
     (index-fields (scope-index (:volume h) cc (:sloc locs) locs)))))

(defn- file-index
  "A file's index is the original formula: the average volume, complexity and size
  of its definitions, plus its own comment ratio.  Summing them instead would make
  it a measure of how long the file is."
  [fns volume locs]
  (let [measured (remove #(zero? (get-in % [:halstead :length])) fns)]
    (when (seq measured)
      (scope-index volume
                   (average (map :cyclomatic measured))
                   (average (map #(get-in % [:locs :sloc]) measured))
                   locs))))

(defn- file-scores
  "A file's totals: its definitions plus the top-level code around them."
  [model fns]
  {:lloc (reduce + 0 (map :lloc fns))
   :cyclomatic (cyclomatic/file-complexity (:top-level-decision-kinds model)
                                           (map :cyclomatic fns))
   :cognitive (+ (cognitive/complexity (:top-level-flow model))
                 (reduce + 0 (map :cognitive fns)))})

(defn- language-of
  "The adapter for a path, which has to have one."
  [path]
  (or (language/of path)
      (throw (ex-info (str "not a language we analyze: " path) {:path path}))))

(defn- file-facts
  "What a file is, apart from its scores."
  [path lang model]
  (merge
   (select-keys model [:libspecs :symbols :qualifiers :load-paths])
   {:path path
    :namespace (some-> (:namespace model) str)
    :language (:id lang)
    :unused-public? (:unused-public? lang)
    :imports (mapv str (:imports model))}))

(defn analyze-source
  "Every metric for one file's source."
  [path source]
  (let [lang (language-of path)
        {:keys [forms dependencies]} ((:parse lang) source)
        model (reader/analyze-forms path forms dependencies)
        scanned ((:scan lang) source)
        locs (loc/tally (:classes scanned))
        fns (mapv #(function-metrics % scanned) (:functions model))
        file-h (average-halstead (map :halstead fns))]
    (merge
     (file-facts path lang model)
     {:loc locs
      :functions (mapv #(dissoc % :halstead) fns)
      :halstead file-h}
     (file-scores model fns)
     (index-fields (file-index fns (:volume file-h) locs)))))

(defn summarize
  "Project-level rollups over per-file metrics; unreadable is how many files
  could not be analyzed at all.  The averages are per function, and the
  maintainability one covers only files with a defined, comment-free index."
  [files unreadable]
  (let [n (count files)
        fns (mapcat :functions files)]
    {:files n
     :unreadable unreadable
     :loc (reduce + 0 (map (comp :loc :loc) files))
     :sloc (reduce + 0 (map (comp :sloc :loc) files))
     :cloc (reduce + 0 (map (comp :cloc :loc) files))
     :functions (count fns)
     :cyclomatic (reduce + 0 (map :cyclomatic files))
     :cognitive (reduce + 0 (map :cognitive files))
     :avg-cyclomatic (average (map :cyclomatic fns))
     :avg-maintainability (average (keep :maintainability-index-without-comments files))
     :avg-instability (average (keep :instability files))}))

(defn- risk-fn
  "A function giving a file's hotspot risk, or nil when it has too few changes to
  say anything.  Test files aren't hotspots, and don't set the scale either."
  [files]
  (let [code (remove :test? files)
        max-changes (reduce max 0 (keep :changes code))
        max-complexity (reduce max 0 (map :cyclomatic code))]
    (fn [{:keys [changes cyclomatic test?]}]
      (when (and changes (not test?) (>= changes queue/min-hotspot-changes))
        (hotspot/risk max-changes max-complexity changes cyclomatic)))))

(def default-test-paths
  "The directories whose code is tests unless a project says otherwise."
  ["test" "tests"])

(defn code-path?
  "Is path, as the analyzer writes it, code that analyze-dir would measure under
  root, given :exclude, :ignored and :test-paths, and not a test?"
  [root {:keys [exclude ignored test-paths]} path]
  (let [rel (paths/relative root path)]
    (boolean (and (language/of path)
                  (not (paths/excluded? exclude rel))
                  (not (paths/ignored? ignored rel))
                  (not (paths/test-file? (or test-paths default-test-paths) rel))))))

(defn- unusual-changes
  "The commits of a history that changed the code's entropy unusually, and how
  many commits it held; nil without one."
  [history]
  (when history
    {:commits (count history)
     :changes (entropy/unusual-changes history)}))

(defn- mark-test-file
  "Everything defined in a test file is test code, helpers included, so it isn't
  queued or called unused."
  [file root test-paths]
  (if (paths/test-file? (or test-paths default-test-paths) (paths/relative root (:path file)))
    (-> file
        (assoc :test? true)
        (update :functions (fn [fns] (mapv #(assoc % :kind :test) fns))))
    file))

(defn- public-file
  "A file's metrics without what only the cleanup and queue passes needed."
  [file]
  (-> file
      (dissoc :symbols :qualifiers :libspecs :unused-public? :load-paths)
      (update :functions (fn [fns] (mapv #(dissoc % :self-refs) fns)))))

(defn- load-all
  "Analyze [path load-source] pairs into {:files :errors}.  A file that cannot be
  loaded or read lands in :errors, and the rest of the project still gets a report."
  [loaders]
  (let [outcomes (mapv (fn [[p load-source]]
                         (try
                           {:file (analyze-source p (load-source))}
                           (catch Throwable e
                             {:error {:path p :error (or (ex-message e) (str e))}})))
                       loaders)]
    {:files (vec (keep :file outcomes))
     :errors (vec (keep :error outcomes))}))

(defn- namespace-targets
  "The project files a file imports by namespace.  A namespace defined by two files
  gets an edge to both."
  [files]
  (let [ns->paths (reduce (fn [m f] (update m (:namespace f) (fnil conj []) (:path f)))
                          {}
                          (filter :namespace files))]
    (fn [f] (mapcat ns->paths (:imports f)))))

(defn- load-targets
  "The project files a file loads by path."
  [files root]
  (let [known (set (map :path files))]
    (fn [f] (keep #(paths/resolve-file known (:path f) % root) (:load-paths f)))))

(defn- dependency-edges
  "[from to] paths for what each file imports or loads from the project."
  [files root]
  (let [imported (namespace-targets files)
        loaded (load-targets files root)]
    (distinct
     (for [f files
           target (concat (imported f) (loaded f))
           :when (not= target (:path f))]
       [(:path f) target]))))

(defn- assoc-coupling
  "Add a file's coupling, given every edge's efferent and afferent maps.  A file
  with no coupling at all is :isolated, which says more than a stable 0 would."
  [f eff aff]
  (let [p (:path f)
        ce (count (get eff p #{}))
        ca (count (get aff p #{}))
        i (when (pos? (+ ce ca)) (coupling/instability ce ca))]
    (assoc f
           :efferent-coupling ce
           :afferent-coupling ca
           :instability i
           :instability-band (if i (coupling/instability-band i) :isolated))))

(defn- annotated-files
  "Files with what takes the whole project to know: coupling, churn and test status."
  [files edges changes mark]
  (let [eff (coupling/efferent edges)
        aff (coupling/afferent edges)]
    (mapv #(assoc (assoc-coupling (mark %) eff aff)
                  :changes (when changes (get changes (:path %) 0)))
          files)))

(defn- analyze-loaded
  "The whole result for [path load-source] pairs.  With :changes, a map of path to
  commit count over the last :days days, it also has churn: hotspots, and a queue
  that puts them first.  With :history, each commit's entropy change as
  metrics.git/history gives it, it has the unusual ones."
  [loaders {:keys [changes days root test-paths history] :as opts}]
  (let [{:keys [files errors]} (load-all loaders)
        edges (dependency-edges files root)
        files (annotated-files files edges changes #(mark-test-file % root test-paths))
        risk-of (risk-fn files)
        result-files (mapv public-file files)]
    {:files result-files
     :errors errors
     :edges (vec edges)
     :summary (summarize result-files (count errors))
     :changes changes
     :days (when changes days)
     :hotspots (queue/hotspots files risk-of)
     :queue (queue/build files risk-of)
     :unusual-changes (unusual-changes history)
     :cleanup (cleanup/candidates-of files (select-keys opts [:root :api-namespaces :api-paths]))}))

(defn analyze-files
  "Analyze a collection of [path source] pairs and resolve coupling between them.
  opts can have :changes and :days for churn, :history for unusual changes,
  :root and :test-paths to say what is test code, and :api-namespaces and
  :api-paths, namespace names or globs and path globs of files whose public
  functions are API and so never unused."
  ([file-sources] (analyze-files file-sources nil))
  ([file-sources opts]
   (analyze-loaded (map (fn [[p s]] [p (constantly s)]) file-sources) opts)))

(defn analyze-dir
  "Analyze every source file of a language we know under a directory, leaving out
  those :exclude globs match and those :ignored lists (paths relative to dir, as
  metrics.git/ignored gives them); other opts as for analyze-files."
  ([dir] (analyze-dir dir nil))
  ([dir {:keys [exclude ignored] :as opts}]
   (let [left-out? #(let [rel (paths/relative dir %)]
                      (or (paths/excluded? exclude rel) (paths/ignored? ignored rel)))
         paths (->> (language/globs)
                    (mapcat #(fs/glob dir %))
                    (map str)
                    (filter language/of)
                    (remove left-out?)
                    distinct)]
     (analyze-loaded (for [p paths] [p #(slurp p)]) (assoc opts :root dir)))))
