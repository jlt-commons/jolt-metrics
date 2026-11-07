(ns metrics.rules
  "The bands a metric value falls in.  The README lists where each limit comes from.")

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
  "The band of a comment-free maintainability index on the 0..100 scale, where
  higher is better.  50 and 38 are the original 85 and 65 limits (Coleman et al.,
  1994) rescaled by 100/171; 10 is where Visual Studio turns red."
  [i]
  (cond
    (> i 50.0) :good
    (> i 38.0) :ok
    (>= i 10.0) :warning
    :else :critical))

(defn flagged?
  "Does this band call for attention?"
  [b]
  (or (= b :warning) (= b :critical)))
