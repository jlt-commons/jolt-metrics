(ns metrics.rules-spec
  "Contract for metrics.rules: the band a metric value falls in.

  A band is :ok, :warning or :critical, given a warning and a critical limit.
  Most metrics are worse higher, and a value over a limit has broken it.  The
  maintainability index is worse lower, and a value at or under the warning limit,
  or under the critical one, has broken it.  Hotspot risk breaks a limit by
  reaching it."
  (:require [writ.spec :refer [spec ann law graph]]
            [metrics.rules :as rules]))

(spec metrics.rules {:require :proved})

(ann band [Int Int Int -> Keyword])
(ann low-band [Double Double Double -> Keyword])
(ann from-band [Double Double Double -> Keyword])
(ann flagged? [Keyword -> Bool])

(graph rules
  {:states {:value Int, :index Double, :band Keyword, :flag Bool}
   :edges  {:value {[band Int Int] #{:band}}
            :index {[low-band Double Double] #{:band} [from-band Double Double] #{:band}}
            :band  {[flagged?] #{:flag}}}})

(law band-splits-the-line
  (forall [warning Int, critical Int, v Int]
    (cond
      (> v critical) (= (rules/band warning critical v) :critical)
      (> v warning)  (= (rules/band warning critical v) :warning)
      :else          (= (rules/band warning critical v) :ok))))

(law band-limits-are-inclusive
  (and (= (rules/band 10 20 10) :ok)
       (= (rules/band 10 20 11) :warning)
       (= (rules/band 10 20 20) :warning)
       (= (rules/band 10 20 21) :critical)))

(law low-band-splits-the-line
  (forall [warning Double, critical Double, i Double]
    (cond
      (< i critical) (= (rules/low-band warning critical i) :critical)
      (<= i warning) (= (rules/low-band warning critical i) :warning)
      :else          (= (rules/low-band warning critical i) :ok))))

(law low-band-boundaries
  (and (= (rules/low-band 38.0 10.0 38.5) :ok)
       (= (rules/low-band 38.0 10.0 38.0) :warning)
       (= (rules/low-band 38.0 10.0 10.0) :warning)
       (= (rules/low-band 38.0 10.0 9.5) :critical)))

(law from-band-splits-the-line
  (forall [warning Double, critical Double, r Double]
    (cond
      (>= r critical) (= (rules/from-band warning critical r) :critical)
      (>= r warning)  (= (rules/from-band warning critical r) :warning)
      :else           (= (rules/from-band warning critical r) :ok))))

(law from-band-boundaries
  (and (= (rules/from-band 0.5 0.8 0.49) :ok)
       (= (rules/from-band 0.5 0.8 0.5) :warning)
       (= (rules/from-band 0.5 0.8 0.8) :critical)))

(law only-warning-and-critical-are-flagged
  (forall [b Keyword]
    (= (rules/flagged? b) (or (= b :warning) (= b :critical)))))
