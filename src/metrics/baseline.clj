(ns metrics.baseline
  "The queue against one from an earlier run, to check what a refactor did: the
  entries it fixed, those it made better or worse, and those that are new.
  This is the evaluate step of the Agentic Algorithm Engineering cycle: measure
  after the change, against the same yardstick as before.

  Entries match by kind, path and name, so a definition that moved is the same
  entry; a renamed one is fixed under its old name and new under its new one.")

(defn- entry-key [e]
  [(:kind e) (:path e) (:name e)])

(defn- changed-metrics
  "The metrics whose value moved, as {metric [before after]}."
  [before after]
  (into {}
        (for [[k v] (:metrics after)
              :let [old (get-in before [:metrics k])]
              :when (and (some? old) (some? v) (not= old v))]
          [k [old v]])))

(defn- change [before after]
  {:entry after :before-score (:score before) :changed (changed-metrics before after)})

(defn compare-queues
  "How the queue after differs from the queue before: {:fixed :improved :worse
  :new :unchanged}.  :fixed and :new hold entries, :improved and :worse hold
  {:entry :before-score :changed}, biggest change first, and :unchanged is a
  count."
  [before after]
  (let [old (into {} (map (juxt entry-key identity)) before)
        now (into {} (map (juxt entry-key identity)) after)
        both (for [e after :let [b (get old (entry-key e))] :when b] [b e])
        moved (fn [pred] (->> both
                              (filter (fn [[b e]] (pred (:score e) (:score b))))
                              (sort-by (fn [[b e]] (- (abs (- (:score e) (:score b))))))
                              (mapv (fn [[b e]] (change b e)))))]
    {:fixed (filterv #(not (contains? now (entry-key %))) before)
     :improved (moved <)
     :worse (moved >)
     :new (filterv #(not (contains? old (entry-key %))) after)
     :unchanged (count (filter (fn [[b e]] (== (:score b) (:score e))) both))}))

(defn with-baseline
  "result with :since-baseline, how its queue differs from before, an earlier
  run's queue; result as it is when there is none."
  [result before]
  (if before
    (assoc result :since-baseline (compare-queues before (:queue result)))
    result))
