(ns metrics.ignore
  "Leave out what ignore markers cover.  A marker (see metrics.scanner) covers the
  lines of the form after it, for every rule or the ones it names: lint findings,
  the queue rules a definition broke, and unused function candidates.  After
  clj-kondo (https://github.com/clj-kondo/clj-kondo), a marker of ours that leaves
  nothing out is itself a :redundant-ignore finding, and clj-kondo's own marker
  covers lint findings, which follow its rules, and is never redundant."
  (:require [metrics.lint :as lint]
            [metrics.queue :as queue]))

(defn- covers? [{:keys [from to rules]} line rule]
  (boolean (and line (<= from line to) (or (= :all rules) (contains? rules rule)))))

(defn- marker-id [path m]
  [path (:line m) (:column m)])

(defn- covering
  "The markers that cover rule at line; clj-kondo's only when kondo? is set."
  [markers line rule kondo?]
  (filter #(and (or kondo? (not (:clj-kondo %))) (covers? % line rule)) markers))

(defn- sift
  "[the items no marker covers, the ids of the markers that cover any]."
  [items ignores rule-of kondo?]
  (reduce (fn [[kept used] {:keys [path line] :as item}]
            (let [ms (covering (get ignores path) line (rule-of item) kondo?)]
              (if (seq ms)
                [kept (into used (map #(marker-id path %) ms))]
                [(conj kept item) used])))
          [[] #{}]
          items))

(defn- ignored-rules
  "The violation rules of a queue entry that markers cover, and the ids of those
  markers."
  [ignores {:keys [path line violations]}]
  (let [rules (set (map :rule violations))
        hits (filter #(and (not (:clj-kondo %))
                           (some (fn [r] (covers? % line r)) rules))
                     (get ignores path))]
    [(if (some #(= :all (:rules %)) hits) rules (set (mapcat :rules hits)))
     (map #(marker-id path %) hits)]))

(defn- sift-queue [entries ignores]
  (reduce (fn [[kept used] e]
            (let [[rules ids] (ignored-rules ignores e)
                  left (if (seq rules) (queue/without-rules e rules) e)]
              [(if left (conj kept left) kept) (into used ids)]))
          [[] #{}]
          entries))

(defn- redundant
  "A :redundant-ignore finding for each marker of ours whose id isn't in used."
  [ignores used]
  (for [[path ms] (sort-by key ignores)
        m ms
        :when (and (not (:clj-kondo m)) (not (contains? used (marker-id path m))))]
    {:path path :line (:line m) :column (:column m)
     :rule :redundant-ignore :message "Redundant ignore"}))

(defn- sift-cleanup [cleanup ignores]
  (let [[private used-a] (sift (:unused-private cleanup) ignores (constantly :unused-private) false)
        [public used-b] (sift (:unused-public cleanup) ignores (constantly :unreferenced-public) false)]
    [(assoc cleanup :unused-private private :unused-public public) (into used-a used-b)]))

(defn- project-lints
  "Every file's lint findings at the levels linters gives them."
  [files linters]
  (for [f files
        finding (lint/with-levels (:lints f) linters)]
    (assoc finding :path (:path f))))

(defn reviewed
  "findings, {:queue :cleanup}, with the lint findings of analyzed files at the
  levels linters gives them, less what the files' markers cover; and a
  :redundant-ignore lint for each marker of ours that left nothing out."
  [{:keys [queue cleanup] :as findings} files linters]
  (let [ignores (into {} (map (juxt :path :ignores)) files)
        [lints used-lints] (sift (project-lints files linters) ignores :rule true)
        [queue used-queue] (sift-queue queue ignores)
        [cleanup used-cleanup] (sift-cleanup cleanup ignores)
        extra (lint/with-levels (redundant ignores (into used-lints (concat used-queue used-cleanup)))
                                linters)]
    (assoc findings
           :lints (vec (sort-by (juxt :path :line :column) (concat lints extra)))
           :queue queue
           :cleanup cleanup)))
