(ns metrics.baseline
  "The queue against one from an earlier run, to check what a refactor did: the
  entries it fixed, those it made better or worse, and those that are new.
  This is the evaluate step of the Agentic Algorithm Engineering cycle: measure
  after the change, against the same yardstick as before.

  Entries match by kind, path and name, so a definition that moved is the same
  entry; a renamed one is fixed under its old name and new under its new one.
  Paths are as the analyzer wrote them, so both runs need the same directory
  argument.  An entry an ignore marker now hides has left the queue, and
  counts as fixed.")

(defn- entry-key [e]
  [(:kind e) (:path e) (:name e)])

(defn- keyed
  "Each entry under its key and its place among the entries sharing it, so two
  definitions of one name pair up in order rather than collapse into one."
  [entries]
  (first (reduce (fn [[out seen] e]
                   (let [k (entry-key e)
                         i (get seen k 0)]
                     [(conj out [(conj k i) e]) (assoc seen k (inc i))]))
                 [[] {}]
                 entries)))

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
  :new :unchanged :not-analyzed}.  :fixed, :new and :not-analyzed hold
  entries, :improved and :worse hold {:entry :before-score :changed}, biggest
  change first, and :unchanged is a count.  With analyzed, the paths the later
  run read, an entry that left the queue because its file wasn't read (it no
  longer parses, or is excluded) is :not-analyzed, not :fixed."
  ([before after] (compare-queues before after nil))
  ([before after analyzed]
   (let [old (keyed before)
         now (keyed after)
         old-by (into {} old)
         now-by (into {} now)
         both (for [[k e] now :let [b (get old-by k)] :when b] [b e])
         gone (for [[k b] old :when (not (contains? now-by k))] b)
         read? (fn [e] (or (nil? analyzed) (contains? analyzed (:path e))))
         moved (fn [pred] (->> both
                               (filter (fn [[b e]] (pred (:score e) (:score b))))
                               (sort-by (fn [[b e]] (- (abs (- (:score e) (:score b))))))
                               (mapv (fn [[b e]] (change b e)))))]
     {:fixed (filterv read? gone)
      :improved (moved <)
      :worse (moved >)
      :new (mapv second (remove (fn [[k _]] (contains? old-by k)) now))
      :unchanged (count (filter (fn [[b e]] (== (:score b) (:score e))) both))
      :not-analyzed (vec (remove read? gone))})))

(defn with-baseline
  "result with :since-baseline, how its queue differs from before, an earlier
  run's queue; result as it is when there is none."
  [result before]
  (if before
    (assoc result :since-baseline
           (compare-queues before (:queue result) (set (map :path (:files result)))))
    result))
