(ns metrics.coupling-spec
  "Contract for metrics.coupling: efferent/afferent coupling and instability.

  efferent maps each module to the set it depends on; afferent is the inverse.
  Instability is Ce/(Ce+Ca), 0 with no coupling at all."
  (:require [writ.spec :refer [spec ann law refine graph]]
            [metrics.coupling :as cp]))

(spec metrics.coupling {:require :proved})

(ann efferent    [(List (Tuple String String)) -> (Map String (Set String))])
(ann afferent    [(List (Tuple String String)) -> (Map String (Set String))])
(ann instability [Nat Nat -> Double])
(ann instability-band [Double -> Keyword])

(graph coupling
  {:states {:edges (List (Tuple String String)), :deps (Map String (Set String))}
   :edges  {:edges {[efferent] #{:deps} [afferent] #{:deps}}}})

;; --- instability ---------------------------------------------------------

(law instability-is-ce-over-total
  {:require :tested :because "the prover does not model division"}
  (forall [ce Nat, ca Nat]
    (=> (pos? (+ ce ca)) (= (cp/instability ce ca) (/ ce (+ ce ca))))))

(law no-coupling-is-zero
  (= (cp/instability 0 0) 0))

(law instability-lies-in-the-unit-interval
  {:require :tested :because "the prover does not model division"}
  (forall [ce Nat, ca Nat]
    (and (<= 0 (cp/instability ce ca)) (<= (cp/instability ce ca) 1))))

(law instability-example
  (= (cp/instability 3 1) 3/4))

(law instability-bands
  (and (= (cp/instability-band 0.0) :stable)
       (= (cp/instability-band 0.3) :stable)
       (= (cp/instability-band 0.5) :neutral)
       (= (cp/instability-band 0.7) :neutral)
       (= (cp/instability-band 0.8) :unstable)))

(law band-partitions-the-line
  (forall [i Double]
    (cond
      (<= i 0.3)  (= (cp/instability-band i) :stable)
      (> i 0.7)   (= (cp/instability-band i) :unstable)
      :else       (= (cp/instability-band i) :neutral))))

;; --- efferent / afferent are inverses ------------------------------------

(law every-edge-shows-in-both-directions
  {:require :tested
   :because "conj onto a set is outside the prover's model"}
  (forall [es (List (Tuple String String))]
    (every? (fn [[from to]]
              (and (contains? (get (cp/efferent es) from #{}) to)
                   (contains? (get (cp/afferent es) to #{}) from)))
            es)))

(law efferent-example
  (= (cp/efferent [["a" "b"] ["a" "c"] ["b" "c"]])
     {"a" #{"b" "c"} "b" #{"c"}}))

(law afferent-example
  (= (cp/afferent [["a" "b"] ["a" "c"] ["b" "c"]])
     {"b" #{"a"} "c" #{"a" "b"}}))
