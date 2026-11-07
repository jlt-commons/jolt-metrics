(ns metrics.halstead-spec
  "Contract for metrics.halstead: the Halstead complexity metrics.

  Operators and operands are sequences of names.  n1 and n2 count the distinct
  operators and operands, N1 and N2 the total occurrences; from these the
  standard Halstead volume, difficulty, effort, time and estimated-bug count
  follow."
  (:require [writ.spec :refer [spec ann law example data refine graph assume]]
            [clojure.math :as math]
            [metrics.halstead :as h]))

(spec metrics.halstead {:require :proved})

;; the code's only non-core dependency is the base-2 logarithm
(assume math/log [Double -> Double])

(refine Metrics
  [m {:n1 Nat :n2 Nat :N1 Nat :N2 Nat
      :vocabulary Nat :length Nat
      :estimated-length Double :volume Double :difficulty Double
      :effort Double :time Double :bugs Double}]
  true)

(ann calculate [(List String) (List String) -> Metrics])

;; a plain-function spec still has a graph: the data the problem moves through,
;; the operator list in, the metrics out
(graph halstead
  {:states {:operators (List String), :metrics Metrics}
   :edges  {:operators {[calculate (List String)] #{:metrics}}}})

;; --- the spec's model: the formula, recomputed from the definitions ------

(defn log2 [x] (/ (math/log (double x)) (math/log 2.0)))
(defn n-distinct [xs] (count (distinct xs)))

(defn model
  "The Halstead metrics straight from the definitions, for the laws to compare
  against."
  [ops ods]
  (let [n1 (n-distinct ops) n2 (n-distinct ods)
        N1 (count ops) N2 (count ods)
        n  (+ n1 n2) N (+ N1 N2)
        hat (if (and (pos? n1) (pos? n2)) (+ (* n1 (log2 n1)) (* n2 (log2 n2))) 0.0)
        V   (if (pos? n) (* N (log2 n)) 0.0)
        D   (if (pos? n2) (* (/ n1 2.0) (/ N2 n2)) 0.0)
        E   (* D V)]
    {:n1 n1 :n2 n2 :N1 N1 :N2 N2 :vocabulary n :length N
     :estimated-length hat :volume V :difficulty D :effort E
     :time (/ E 18.0) :bugs (if (pos? V) (/ V 3000.0) 0.0)}))

;; --- a worked scope: 3 operators (2 distinct), 3 operands (2 distinct) ---

(def ops ["+" "+" "-"])
(def ods ["a" "b" "a"])

(law n1-is-distinct-operators  (= (:n1 (h/calculate ops ods)) 2))
(law n2-is-distinct-operands  (= (:n2 (h/calculate ops ods)) 2))
(law N1-is-total-operators    (= (:N1 (h/calculate ops ods)) 3))
(law N2-is-total-operands     (= (:N2 (h/calculate ops ods)) 3))
(law vocabulary-is-n1-plus-n2 (= (:vocabulary (h/calculate ops ods)) 4))
(law length-is-N1-plus-N2     (= (:length (h/calculate ops ods)) 6))
(law difficulty-is-the-formula (= (:difficulty (h/calculate ops ods)) 1.5))

;; --- the whole result follows the definitions ----------------------------

(law halstead-is-the-model
  {:require :tested
   :because "the model counts distinct tokens and takes base-2 logs, which the prover does not model"}
  (forall [ops (List String), ods (List String)]
    (= (h/calculate ops ods) (model ops ods))))

;; --- edge cases ----------------------------------------------------------

(law an-empty-scope-has-no-volume
  (= (:volume (h/calculate [] [])) 0.0))

;; the Halstead worked example: operators [Assign BinaryOp_Div FuncCall],
;; operand [avg]
(law wikipedia-example
  (= (h/calculate ["Assign" "BinaryOp_Div" "FuncCall"] ["avg"])
     {:n1 3 :n2 1 :N1 3 :N2 1 :vocabulary 4 :length 4
      :estimated-length 4.754887502163469 :volume 8.0 :difficulty 1.5
      :effort 12.0 :time 0.6666666666666666 :bugs 0.0026666666666666666}))

(law distinct-counts-never-exceed-totals
  {:require :tested
   :because "follows from distinct counting, which the prover does not model"}
  (forall [ops (List String), ods (List String)]
    (and (<= (:n1 (h/calculate ops ods)) (:N1 (h/calculate ops ods)))
         (<= (:n2 (h/calculate ops ods)) (:N2 (h/calculate ops ods))))))
