(ns metrics.queue
  "The refactoring queue: the definitions and files over a threshold, worst first,
  each with the rules it broke and what to do about it.  The thresholds are
  metrics.thresholds' map, a project's own merged over the defaults."
  (:require [metrics.rules :as rules]
            [metrics.calls :as calls]))

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

(defn- violation
  "The rule broken, or nil when band b is not flagged."
  [rule value b limits]
  (when (rules/flagged? b)
    {:rule rule
     :value value
     :limit (get limits b)
     :band b}))

(defn- limit-violation
  "A violation of a count's limits, where higher is worse, or nil."
  [thresholds rule value]
  (let [{:keys [warning critical] :as limits} (get thresholds rule)]
    (when value
      (violation rule value (rules/band warning critical value) limits))))

(defn- mi-violation [thresholds mi]
  (let [{:keys [warning critical] :as limits} (:maintainability thresholds)]
    (when mi
      (violation :maintainability mi (rules/low-band (double warning) (double critical) mi) limits))))

(defn- hotspot-violation [thresholds risk]
  (let [{:keys [warning critical] :as limits} (:hotspot thresholds)]
    (when risk
      (violation :hotspot risk (rules/from-band (double warning) (double critical) risk) limits))))

(defn- function-metrics [f]
  {:cyclomatic (:cyclomatic f)
   :cognitive (:cognitive f)
   :nesting (:nesting f)
   :params (:params f)
   :lines (get-in f [:locs :sloc])
   :maintainability (:maintainability-index-without-comments f)
   :fan-in (:fan-in f)
   :fan-out (:fan-out f)
   :reach (:reach f)})

(def ^:private function-rules [:cyclomatic :cognitive :nesting :lines :params])

(defn- function-violations [thresholds metrics]
  (vec (keep identity
             (conj (mapv #(limit-violation thresholds % (get metrics %)) function-rules)
                   (mi-violation thresholds (:maintainability metrics))))))

(defn- file-violations [thresholds file risk]
  (vec (keep identity
             [(hotspot-violation thresholds risk)
              (limit-violation thresholds :file-lines (get-in file [:loc :sloc]))
              (limit-violation thresholds :efferent (:efferent-coupling file))])))

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

(defn- function-entries [thresholds file risk]
  (for [f (:functions file)
        :when (not= :test (:kind f))
        :let [metrics (function-metrics f)
              violations (function-violations thresholds metrics)]
        :when (seq violations)]
    (update
     (entry {:kind (:kind f)
             :name (:name f)
             :path (:path file)
             :line (:line f)
             :end-line (:end-line f)
             :metrics metrics
             :changes (:changes file)}
            violations
            risk)
     ;; what depends on it breaks with it, so the more that does, the sooner
     :score * (calls/reach-factor (or (:reach f) 0)))))

(defn- file-entry
  "A file's own entry; a long test file is not a problem to refactor."
  [thresholds file risk]
  (let [violations (file-violations thresholds file risk)]
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
  [thresholds files risk-of]
  (->> (for [f files
             :let [r (risk-of f)]
             :when (and r (>= r (get-in thresholds [:hotspot :warning])))]
         {:path (:path f)
          :changes (:changes f)
          :complexity (:cyclomatic f)
          :risk r})
       (sort-by (juxt (comp - :risk) :path))
       vec))

(defn build
  "The queue for analyzed files, worst first.  risk-of gives a file's hotspot risk,
  or nil if it has none."
  [thresholds files risk-of]
  (->> files
       (mapcat (fn [f]
                 (let [r (risk-of f)]
                   (concat (function-entries thresholds f r) (file-entry thresholds f r)))))
       (sort-by (fn [e]
                  [(- (:score e))
                   (- (get-in e [:metrics :cognitive] 0))
                   (:path e)
                   (:line e)]))
       vec))
