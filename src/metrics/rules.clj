(ns metrics.rules
  "The band a metric value falls in, given its warning and critical limits.  The
  README lists where each default limit comes from.")

(defn band
  "The band of v where higher is worse: over critical is :critical, over warning
  is :warning, anything else :ok."
  [warning critical v]
  (cond
    (> v critical) :critical
    (> v warning) :warning
    :else :ok))

(defn low-band
  "The band of v where lower is worse, as for the maintainability index: under
  critical is :critical, at or under warning is :warning, anything else :ok."
  [warning critical v]
  (cond
    (< v critical) :critical
    (<= v warning) :warning
    :else :ok))

(defn from-band
  "The band of v where reaching a limit is enough, as for hotspot risk: from
  critical up is :critical, from warning up :warning, anything else :ok."
  [warning critical v]
  (cond
    (>= v critical) :critical
    (>= v warning) :warning
    :else :ok))

(defn flagged?
  "Does this band call for attention?"
  [b]
  (or (= b :warning) (= b :critical)))
