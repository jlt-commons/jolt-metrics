(ns metrics.rules-spec
  "Contract for metrics.rules: the bands a metric value falls in.

  A band is :good, :ok, :warning or :critical.  For most metrics higher is worse,
  so three limits split the line into four bands.  For the maintainability index
  higher is better: above 50 is good, above 38 is ok, and below 10 is critical."
  (:require [writ.spec :refer [spec ann law graph]]
            [metrics.rules :as rules]))

(spec metrics.rules {:require :proved})

(ann band [Nat Nat Nat Nat -> Keyword])
(ann mi-band [Double -> Keyword])
(ann flagged? [Keyword -> Bool])

(graph rules
  {:states {:value Nat, :index Double, :band Keyword, :flag Bool}
   :edges  {:value {[band Nat Nat Nat] #{:band}}
            :index {[mi-band] #{:band}}
            :band  {[flagged?] #{:flag}}}})

(law band-splits-the-line
  (forall [good Nat, ok Nat, warning Nat, v Nat]
    (cond
      (<= v good)    (= (rules/band good ok warning v) :good)
      (<= v ok)      (= (rules/band good ok warning v) :ok)
      (<= v warning) (= (rules/band good ok warning v) :warning)
      :else          (= (rules/band good ok warning v) :critical))))

(law band-limits-are-inclusive
  (and (= (rules/band 5 10 20 5) :good)
       (= (rules/band 5 10 20 6) :ok)
       (= (rules/band 5 10 20 10) :ok)
       (= (rules/band 5 10 20 11) :warning)
       (= (rules/band 5 10 20 20) :warning)
       (= (rules/band 5 10 20 21) :critical)))

(law mi-band-splits-the-line
  (forall [i Double]
    (cond
      (> i 50.0)  (= (rules/mi-band i) :good)
      (> i 38.0)  (= (rules/mi-band i) :ok)
      (>= i 10.0) (= (rules/mi-band i) :warning)
      :else       (= (rules/mi-band i) :critical))))

(law mi-band-boundaries
  (and (= (rules/mi-band 50.5) :good)
       (= (rules/mi-band 50.0) :ok)
       (= (rules/mi-band 38.5) :ok)
       (= (rules/mi-band 38.0) :warning)
       (= (rules/mi-band 10.0) :warning)
       (= (rules/mi-band 9.5) :critical)))

(law only-warning-and-critical-are-flagged
  (forall [b Keyword]
    (= (rules/flagged? b) (or (= b :warning) (= b :critical)))))
