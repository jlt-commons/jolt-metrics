(ns metrics.hotspot-spec
  "Contract for metrics.hotspot: a file's risk from its churn and complexity.

  Normalize both against the project's maxima; risk is how close the file sits to
  the most-changed, most-complex corner: 1 minus the distance, clamped to 0..1."
  (:require [writ.spec :refer [spec ann law graph assume]]
            [clojure.math :as math]
            [metrics.hotspot :as hs]))

(spec metrics.hotspot {:require :proved})

(assume math/sqrt [Double -> Double])

(ann risk [Nat Nat Nat Nat -> Double])

(graph hotspot
  {:states {:max-changes Nat, :risk Double}
   :edges  {:max-changes {[risk Nat Nat Nat] #{:risk}}}})

(defn- clamp [x] (max 0.0 (min 1.0 x)))

(defn- gap [top v]
  (if (pos? top) (clamp (/ (- top v) (double top))) 0.0))

(defn- model [max-changes max-complexity changes complexity]
  (let [h (gap max-changes changes)
        v (gap max-complexity complexity)]
    (clamp (- 1.0 (math/sqrt (+ (* h h) (* v v)))))))

(law the-risk-is-the-model
  {:require :tested
   :because "the distance takes a square root, which the prover does not model"}
  (forall [mc Nat, mx Nat, c Nat, x Nat]
    (= (hs/risk mc mx c x) (model mc mx c x))))

(law risk-lies-in-the-unit-interval
  {:require :tested
   :because "the distance takes a square root, which the prover does not model"}
  (forall [mc Nat, mx Nat, c Nat, x Nat]
    (and (<= 0.0 (hs/risk mc mx c x)) (<= (hs/risk mc mx c x) 1.0))))

(law the-top-corner-is-full-risk
  (= (hs/risk 10 50 10 50) 1.0))

(law the-bottom-corner-is-no-risk
  (= (hs/risk 10 50 0 0) 0.0))

(law churn-alone-is-not-full-risk
  (= (hs/risk 10 50 10 0) 0.0))

(law halfway-on-both-axes
  (= (int (* 1000 (hs/risk 10 10 5 5))) 292))
