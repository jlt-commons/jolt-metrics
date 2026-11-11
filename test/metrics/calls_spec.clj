(ns metrics.calls-spec
  "Contract for metrics.calls: how a function sits in the call graph.

  An edge [caller callee] says caller refers to callee.  Fan-in counts the
  distinct functions that refer to a function, fan-out the distinct functions it
  refers to; a function referring to itself is neither.  Reach is how many
  functions refer to it at any distance, and the reach factor turns that into a
  queue weight: 1 with no callers, rising on a log scale to 2 at reach-cap and
  staying there."
  (:require [writ.spec :refer [spec ann law graph assume]]
            [clojure.math :as math]
            [metrics.calls :as c]))

(spec metrics.calls {:require :proved})

(assume math/log [Double -> Double])

(ann fan-in      [(List (Tuple String String)) -> (Map String Nat)])
(ann fan-out     [(List (Tuple String String)) -> (Map String Nat)])
(ann reach-factor [Nat -> Double])

(graph calls
  {:states {:edges (List (Tuple String String)), :counts (Map String Nat)}
   :edges  {:edges {[fan-in] #{:counts} [fan-out] #{:counts}}}})

;; --- the model ---------------------------------------------------------------

(defn- distinct-others
  "The distinct [from to] pairs that are not a function referring to itself."
  [es]
  (distinct (remove (fn [[a b]] (= a b)) es)))

(defn- model-count [side es]
  (frequencies (map side (distinct-others es))))

;; --- fan-in / fan-out --------------------------------------------------------

(law fan-in-counts-distinct-callers
  {:require :tested :because "frequencies and distinct are outside the prover's model"}
  (forall [es (List (Tuple String String))]
    (= (c/fan-in es) (model-count second es))))

(law fan-out-counts-distinct-callees
  {:require :tested :because "frequencies and distinct are outside the prover's model"}
  (forall [es (List (Tuple String String))]
    (= (c/fan-out es) (model-count first es))))

(law fan-in-example
  (= (c/fan-in [["a" "b"] ["a" "b"] ["c" "b"] ["a" "c"] ["b" "b"]])
     {"b" 2 "c" 1}))

(law fan-out-example
  (= (c/fan-out [["a" "b"] ["a" "b"] ["c" "b"] ["a" "c"] ["b" "b"]])
     {"a" 2 "c" 1}))

(law no-edges-no-counts
  (and (= (c/fan-in []) {}) (= (c/fan-out []) {})))

;; --- the reach factor --------------------------------------------------------

(defn- model-factor [n]
  (+ 1.0 (/ (math/log (+ 1.0 (min n c/reach-cap)))
            (math/log (+ 1.0 c/reach-cap)))))

(law reach-factor-is-the-capped-log-ratio
  {:require :tested :because "the prover does not model log or division"}
  (forall [n Nat]
    (= (c/reach-factor n) (model-factor n))))

(law reach-factor-lies-between-one-and-two
  {:require :tested :because "the prover does not model log or division"}
  (forall [n Nat]
    (and (<= 1.0 (c/reach-factor n)) (<= (c/reach-factor n) 2.0))))

(law reach-factor-never-falls
  {:require :tested :because "the prover does not model log or division"}
  (forall [n Nat]
    (<= (c/reach-factor n) (c/reach-factor (inc n)))))

(law reach-factor-ends
  {:require :tested :because "the prover does not model log or division"}
  (and (= (c/reach-factor 0) 1.0)
       (= (c/reach-factor c/reach-cap) 2.0)
       (= (c/reach-factor (* 10 c/reach-cap)) 2.0)))
