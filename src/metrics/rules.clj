(ns metrics.rules
  "The bands a metric value falls in.

  The cut-offs follow metripy's segments where it has them: 5/10/20 for
  complexity, 15/30/50 lines per function, and 80/60/40 for the maintainability
  index.")

(defn band
  "The band of v, where higher is worse: at most good is :good, at most ok is :ok,
  at most warning is :warning, and anything above is :critical."
  [good ok warning v]
  (cond
    (<= v good) :good
    (<= v ok) :ok
    (<= v warning) :warning
    :else :critical))

(defn mi-band
  "The band of a maintainability index, where higher is better."
  [i]
  (cond
    (> i 80.0) :good
    (> i 60.0) :ok
    (> i 40.0) :warning
    :else :critical))

(defn flagged?
  "Does this band call for attention?"
  [b]
  (or (= b :warning) (= b :critical)))
