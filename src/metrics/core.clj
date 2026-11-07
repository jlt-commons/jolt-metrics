(ns metrics.core
  "The entry point: turn a project's Clojure/Jolt source into a metrics report.

  analyze-source computes every metric for one file; analyze-project resolves
  architecture coupling across files and rolls the project up."
  (:require [metrics.reader :as reader]
            [metrics.loc :as loc]
            [metrics.scanner :as scanner]
            [metrics.cyclomatic :as cyclomatic]
            [metrics.cognitive :as cognitive]
            [metrics.halstead :as halstead]
            [metrics.maintainability :as maintainability]
            [metrics.coupling :as coupling]
            [metrics.hotspot :as hotspot]
            [metrics.queue :as queue]
            [metrics.cleanup :as cleanup]
            [jolt.fs :as fs]
            [clojure.string :as str]))

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

(defn- unit-lines
  "A definition's end line and the classes of the lines it spans."
  [{:keys [line column]} {:keys [classes extents]}]
  (let [end-line (get extents [line column] line)
        start (min (max 0 (dec line)) (count classes))]
    {:end-line end-line
     :locs (loc/tally (subvec classes start (max start (min end-line (count classes)))))}))

(defn- index-fields
  "The index's terms as result fields, all nil when the index is undefined."
  [mi]
  {:maintainability-index (:maintainability-index mi)
   :maintainability-index-without-comments (:maintainability-index-without-comments mi)
   :comment-weight (:comment-weight mi)})

(defn- function-metrics
  "Every metric of one definition.  The index uses the definition's own lines, so
  comments elsewhere in the file cannot change it."
  [f scanned]
  (let [h (halstead/calculate (:operators f) (:operands f))
        cc (cyclomatic/complexity (:decision-kinds f))
        {:keys [end-line locs]} (unit-lines f scanned)
        mi (maintainability/maintainability-index
            (:volume h) cc (:sloc locs) (:loc locs) (:cloc locs))]
    (merge
     {:name (str (:name f))
      :kind (:kind f)
      :line (:line f)
      :end-line end-line
      :locs locs
      :lloc (:lloc f)
      :params (:params f)
      :private (boolean (:private f))
      :self-refs (:self-refs f)
      :cyclomatic cc
      :cognitive (cognitive/complexity (:flow f))
      :nesting (cognitive/max-nesting (:flow f))
      :halstead h
      :halstead-volume (:volume h)}
     (index-fields mi))))

(defn- file-index
  "A file's index is the original formula: the average volume, complexity and size
  of its definitions, plus its own comment ratio.  Summing them instead would make
  it a measure of how long the file is."
  [fns volume locs]
  (let [measured (remove #(zero? (get-in % [:halstead :length])) fns)]
    (when (seq measured)
      (maintainability/maintainability-index
       volume
       (average (map :cyclomatic measured))
       (average (map #(get-in % [:locs :sloc]) measured))
       (:loc locs)
       (:cloc locs)))))

(defn analyze-source
  "Every metric for one file's source."
  [path source]
  (let [model (reader/analyze-file path source)
        scanned (scanner/scan source)
        locs (loc/tally (:classes scanned))
        fns (mapv #(function-metrics % scanned) (:functions model))
        file-h (average-halstead (map :halstead fns))]
    (merge
     (select-keys model [:libspecs :symbols :qualifiers])
     {:path path
      :namespace (some-> (:namespace model) str)
      :language :clojure
      :loc locs
      :lloc (reduce + 0 (map :lloc fns))
      :imports (mapv str (:imports model))
      :functions (mapv #(dissoc % :halstead) fns)
      :cyclomatic (cyclomatic/file-complexity (:top-level-decision-kinds model)
                                              (map :cyclomatic fns))
      :cognitive (+ (cognitive/complexity (:top-level-flow model))
                    (reduce + 0 (map :cognitive fns)))
      :halstead file-h}
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
  say anything."
  [files]
  (let [max-changes (reduce max 0 (keep :changes files))
        max-complexity (reduce max 0 (map :cyclomatic files))]
    (fn [{:keys [changes cyclomatic]}]
      (when (and changes (>= changes queue/min-hotspot-changes))
        (hotspot/risk max-changes max-complexity changes cyclomatic)))))

(defn- public-file
  "A file's metrics without what only the cleanup and queue passes needed."
  [file]
  (-> file
      (dissoc :symbols :qualifiers :libspecs)
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

(defn- dependency-edges
  "[from to] paths for each import of a namespace in the project.  A namespace
  defined by two files gets an edge to both."
  [files]
  (let [ns->paths (reduce (fn [m f] (update m (:namespace f) (fnil conj []) (:path f)))
                          {}
                          (filter :namespace files))]
    (distinct
     (for [f files
           imp (:imports f)
           target (get ns->paths imp)
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

(defn- analyze-loaded
  "The whole result for [path load-source] pairs.  With :changes, a map of path to
  commit count over the last :days days, it also has churn: hotspots, and a queue
  that puts them first."
  [loaders {:keys [changes days]}]
  (let [{:keys [files errors]} (load-all loaders)
        edges (dependency-edges files)
        eff (coupling/efferent edges)
        aff (coupling/afferent edges)
        files (mapv #(assoc (assoc-coupling % eff aff)
                            :changes (when changes (get changes (:path %) 0)))
                    files)
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
     :cleanup (cleanup/candidates-of files)}))

(defn analyze-files
  "Analyze a collection of [path source] pairs and resolve coupling between them."
  ([file-sources] (analyze-files file-sources nil))
  ([file-sources opts]
   (analyze-loaded (map (fn [[p s]] [p (constantly s)]) file-sources) opts)))

(def source-extensions #{"clj" "cljs" "cljc" "jolt"})

(defn analyze-dir
  "Analyze every Clojure/Jolt source file under a directory; opts as for
  analyze-files."
  ([dir] (analyze-dir dir nil))
  ([dir opts]
   (let [paths (for [p (fs/glob dir "**.clj*")
                     :let [ext (str/lower-case (or (fs/extension p) ""))]
                     :when (contains? source-extensions ext)]
                 (str p))
         paths (concat paths (map str (fs/glob dir "**.jolt")))]
     (analyze-loaded (for [p (distinct paths)] [p #(slurp p)]) opts))))
