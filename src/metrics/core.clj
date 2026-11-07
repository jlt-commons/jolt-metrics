(ns metrics.core
  "The entry point: turn a project's Clojure/Jolt source into a metrics report.

  analyze-source computes every metric for one file; analyze-project resolves
  architecture coupling across files and rolls the project up."
  (:require [metrics.reader :as reader]
            [metrics.loc :as loc]
            [metrics.cyclomatic :as cyclomatic]
            [metrics.cognitive :as cognitive]
            [metrics.halstead :as halstead]
            [metrics.maintainability :as maintainability]
            [metrics.coupling :as coupling]
            [jolt.fs :as fs]
            [clojure.string :as str]))

(defn- average-halstead [hs]
  (let [hs (remove nil? hs)
        n (count hs)]
    (if (zero? n)
      (halstead/calculate [] [])
      (let [sums (reduce (fn [acc h] (merge-with + acc h))
                         (halstead/calculate [] [])
                         hs)]
        (into {} (map (fn [[k v]] [k (/ v n)]) sums))))))

(defn analyze-source
  "Every metric for one file's source."
  [path source]
  (let [model (reader/analyze-file path source)
        locs (loc/count-lines source)
        fns (for [f (:functions model)
                  :let [h (halstead/calculate (:operators f) (:operands f))
                        cc (cyclomatic/complexity (:decision-kinds f))
                        cog (cognitive/complexity (:flow f))
                        mi (maintainability/maintainability-index
                            (:volume h) cc (:lloc f) (:loc locs) (:cloc locs))]]
              {:name (str (:name f))
               :line (:line f)
               :lloc (:lloc f)
               :cyclomatic cc
               :cognitive cog
               :halstead-volume (:volume h)
               :maintainability-index (:maintainability-index mi)
               :maintainability-index-without-comments (:maintainability-index-without-comments mi)
               :comment-weight (:comment-weight mi)})
        file-h (average-halstead (for [f (:functions model)]
                                   (halstead/calculate (:operators f) (:operands f))))
        file-cc (cyclomatic/file-complexity
                 (:top-level-decision-kinds model)
                 (map :cyclomatic fns))
        file-lloc (reduce + 0 (map :lloc fns))
        file-mi (maintainability/maintainability-index
                 (:volume file-h) file-cc file-lloc (:loc locs) (:cloc locs))]
    {:path path
     :namespace (some-> (:namespace model) str)
     :language :clojure
     :loc locs
     :lloc file-lloc
     :imports (mapv str (:imports model))
     :functions (vec fns)
     :cyclomatic file-cc
     :cognitive (reduce + 0 (map :cognitive fns))
     :halstead file-h
     :maintainability-index (:maintainability-index file-mi)
     :maintainability-index-without-comments (:maintainability-index-without-comments file-mi)
     :comment-weight (:comment-weight file-mi)}))

(defn- average
  "The mean of xs, or nil when there is nothing to average."
  [xs]
  (when (seq xs)
    (/ (reduce + 0 xs) (count xs))))

(defn summarize
  "Project-level rollups over per-file metrics; unreadable is how many files
  could not be analyzed at all."
  [files unreadable]
  (let [n (count files)]
    {:files n
     :unreadable unreadable
     :loc (reduce + 0 (map (comp :loc :loc) files))
     :sloc (reduce + 0 (map (comp :sloc :loc) files))
     :cloc (reduce + 0 (map (comp :cloc :loc) files))
     :functions (reduce + 0 (map (comp count :functions) files))
     :cyclomatic (reduce + 0 (map :cyclomatic files))
     :cognitive (reduce + 0 (map :cognitive files))
     :avg-cyclomatic (if (pos? n) (/ (reduce + 0 (map :cyclomatic files)) n) 0)
     :avg-maintainability (average (keep :maintainability-index files))
     :avg-instability (if (pos? n) (/ (reduce + 0 (map :instability files)) n) 0)}))

(defn- analyze-loaded
  "Analyze [path load-source] pairs.  A file that cannot be loaded or read is
  recorded under :errors and the rest of the project still gets a report."
  [loaders]
  (let [outcomes (mapv (fn [[p load-source]]
                         (try
                           {:file (analyze-source p (load-source))}
                           (catch Throwable e
                             {:error {:path p :error (or (ex-message e) (str e))}})))
                       loaders)
        errors (vec (keep :error outcomes))
        files (vec (keep :file outcomes))
        ns->path (into {} (for [f files :when (:namespace f)] [(:namespace f) (:path f)]))
        edges (for [f files
                    imp (:imports f)
                    :let [target (get ns->path imp)]
                    :when (and target (not= target (:path f)))]
                [(:path f) target])
        eff (coupling/efferent edges)
        aff (coupling/afferent edges)
        files (mapv (fn [f]
                      (let [p (:path f)
                            ce (count (get eff p #{}))
                            ca (count (get aff p #{}))
                            i (coupling/instability ce ca)]
                        (assoc f
                               :efferent-coupling ce
                               :afferent-coupling ca
                               :instability i
                               :instability-band (coupling/instability-band i))))
                    files)]
    {:files files
     :errors errors
     :edges (vec edges)
     :summary (summarize files (count errors))}))

(defn analyze-files
  "Analyze a collection of [path source] pairs and resolve coupling between them."
  [file-sources]
  (analyze-loaded (map (fn [[p s]] [p (constantly s)]) file-sources)))

(def source-extensions #{"clj" "cljs" "cljc" "jolt"})

(defn analyze-dir
  "Analyze every Clojure/Jolt source file under a directory."
  [dir]
  (let [paths (for [p (fs/glob dir "**.clj*")
                    :let [ext (str/lower-case (or (fs/extension p) ""))]
                    :when (contains? source-extensions ext)]
                (str p))
        paths (concat paths (map str (fs/glob dir "**.jolt")))]
    (analyze-loaded (for [p (distinct paths)] [p #(slurp p)]))))
