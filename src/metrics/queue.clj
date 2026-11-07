(ns metrics.queue
  "The refactoring queue: the definitions and files over a threshold, worst first,
  each with the rules it broke and what to do about it."
  (:require [metrics.rules :as rules]))

(def function-limits
  "[good ok warning] per function, where higher is worse.  Above ok is a warning
  and above warning is critical.  The README lists where each limit comes from."
  {:cyclomatic [5 10 20]
   :cognitive [5 15 25]
   :nesting [2 3 5]
   :params [3 4 7]
   :lines [10 20 50]})

(def file-limits
  "[good ok warning] per file: source lines as in metripy, dependencies as in
  ast-metrics."
  {:file-lines [200 500 1000]
   :efferent [10 19 39]})

(def hotspot-limits
  "The risk that makes a file a hotspot, and the risk that makes it critical."
  {:warning 0.5 :critical 0.8})

(def min-hotspot-changes
  "Fewer changes than this say nothing about where the work is going."
  3)

(def ^:private actions
  {:hotspot "It is complex and keeps changing, so refactor it first, with tests in place."
   :cognitive "Flatten the control flow: extract inner branches so it reads top to bottom."
   :cyclomatic "Split the branches into named functions or a dispatch map."
   :nesting "Extract the innermost blocks into named functions."
   :lines "Break it into smaller functions with one job each."
   :params "Group related parameters into a map."
   :maintainability "Shrink it: extract helpers and reduce the vocabulary it juggles."
   :file-lines "Split the namespace along its responsibilities."
   :efferent "Too many dependencies: split it, or put a narrower namespace in front of them."})

(def ^:private action-order
  [:hotspot :cognitive :cyclomatic :nesting :lines :params :maintainability :file-lines :efferent])

(def ^:private band-weight {:warning 1 :critical 3})

(defn- limit-violation
  "A violation of the [good ok warning] limits, or nil if value is within them."
  [rule [good ok warning] value]
  (let [b (rules/band good ok warning value)]
    (when (rules/flagged? b)
      {:rule rule
       :value value
       :limit (if (= :critical b) warning ok)
       :band b})))

(def ^:private mi-limits
  {:warning 38.0 :critical 10.0})

(defn- mi-violation [mi]
  (when mi
    (let [b (rules/mi-band mi)]
      (when (rules/flagged? b)
        {:rule :maintainability :value mi :limit (get mi-limits b) :band b}))))

(defn- function-metrics [f]
  {:cyclomatic (:cyclomatic f)
   :cognitive (:cognitive f)
   :nesting (:nesting f)
   :params (:params f)
   :lines (get-in f [:locs :sloc])
   :maintainability (:maintainability-index-without-comments f)})

(defn- function-violations [metrics]
  (vec (keep identity
             (conj (mapv (fn [[rule limit]] (limit-violation rule limit (get metrics rule)))
                         function-limits)
                   (mi-violation (:maintainability metrics))))))

(defn- hotspot-violation [risk]
  (when risk
    (cond
      (>= risk (:critical hotspot-limits))
      {:rule :hotspot :value risk :limit (:critical hotspot-limits) :band :critical}
      (>= risk (:warning hotspot-limits))
      {:rule :hotspot :value risk :limit (:warning hotspot-limits) :band :warning}
      :else nil)))

(defn- file-violations [file risk]
  (vec (keep identity
             [(hotspot-violation risk)
              (limit-violation :file-lines (:file-lines file-limits) (get-in file [:loc :sloc]))
              (limit-violation :efferent (:efferent file-limits) (:efferent-coupling file))])))

(defn- entry
  "A queue entry from its violations.  Its score weighs them, and a file's risk
  raises the score of everything in it."
  [base violations risk]
  (let [broken (set (map :rule violations))
        weight (reduce + 0 (map (comp band-weight :band) violations))]
    (assoc base
           :violations violations
           :severity (if (some #(= :critical (:band %)) violations) :critical :warning)
           :score (* weight (+ 1.0 (or risk 0.0)))
           :actions (vec (take 2 (keep #(when (contains? broken %) (get actions %)) action-order))))))

(defn- function-entries [file risk]
  (for [f (:functions file)
        :when (not= :test (:kind f))
        :let [metrics (function-metrics f)
              violations (function-violations metrics)]
        :when (seq violations)]
    (entry {:kind (:kind f)
            :name (:name f)
            :path (:path file)
            :line (:line f)
            :end-line (:end-line f)
            :metrics metrics
            :changes (:changes file)}
           violations
           risk)))

(defn- file-entry
  "A file's own entry; a long test file is not a problem to refactor."
  [file risk]
  (let [violations (file-violations file risk)]
    (when (and (seq violations) (not (:test? file)))
      [(entry {:kind :file
               :name (:path file)
               :path (:path file)
               :line 1
               :end-line (get-in file [:loc :loc])
               :changes (:changes file)}
              violations
              risk)])))

(defn hotspots
  "The files that are both complex and changing, riskiest first, as
  {:path :changes :complexity :risk}."
  [files risk-of]
  (->> (for [f files
             :let [r (risk-of f)]
             :when (and r (>= r (:warning hotspot-limits)))]
         {:path (:path f)
          :changes (:changes f)
          :complexity (:cyclomatic f)
          :risk r})
       (sort-by (juxt (comp - :risk) :path))
       vec))

(defn build
  "The queue for analyzed files, worst first.  risk-of gives a file's hotspot risk,
  or nil if it has none."
  [files risk-of]
  (->> files
       (mapcat (fn [f]
                 (let [r (risk-of f)]
                   (concat (function-entries f r) (file-entry f r)))))
       (sort-by (fn [e]
                  [(- (:score e))
                   (- (get-in e [:metrics :cognitive] 0))
                   (:path e)
                   (:line e)]))
       vec))
