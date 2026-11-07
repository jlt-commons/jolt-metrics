(ns metrics.maintainability-spec
  "Contract for metrics.maintainability: the Maintainability Index.

  MIwoc = 171 - 5.2*ln(V) - 0.23*G - 16.2*ln(lloc), scaled to 0..100 and
  clamped at 0; the comment term MIcw = 50*sin(sqrt(2.4*cloc/loc)); the index is
  their sum.  Undefined (nil) when V <= 0 or lloc <= 0."
  (:require [writ.spec :refer [spec ann law refine graph assume]]
            [clojure.math :as math]
            [metrics.maintainability :as mi]))

(spec metrics.maintainability {:require :proved})

;; the code's only non-core dependencies are the transcendental functions
(assume math/log [Double -> Double])
(assume math/sin [Double -> Double])
(assume math/sqrt [Double -> Double])

(refine Mi
  [m {:maintainability-index Double
      :maintainability-index-without-comments Double
      :comment-weight Double}]
  true)

(ann maintainability-index [Double Nat Nat Nat Nat -> (Opt Mi)])

;; the sub-terms are public so the spec states them separately
(ann mi-without-comments [Double Nat Nat -> Double])
(ann comment-weight [Nat Nat -> Double])

(graph mi
  {:states {:volume Double, :index (Opt Mi)}
   :edges  {:volume {[maintainability-index Nat Nat Nat Nat] #{:index}}}})

;; --- the spec's model: the formula, recomputed ---------------------------

(defn model-woc [V G lloc]
  (max (* (- 171
             (* 5.2 (math/log (double V)))
             (* 0.23 G)
             (* 16.2 (math/log (double lloc))))
          (/ 100 171))
       0))

(defn model-cw [loc cloc]
  (if (pos? loc)
    (* 50 (math/sin (math/sqrt (* 2.4 (/ cloc loc)))))
    0.0))

(defn model [V G lloc loc cloc]
  (when (and (pos? V) (pos? lloc))
    (let [woc (model-woc V G lloc)
          cw  (model-cw loc cloc)]
      {:maintainability-index (+ woc cw)
       :maintainability-index-without-comments woc
       :comment-weight cw})))

;; --- the index follows the formula ---------------------------------------

(law the-index-is-the-model
  {:require :tested
   :because "the formula takes logs, sines and square roots, which the prover does not model"}
  (forall [V Double, G Nat, lloc Nat, loc Nat, cloc Nat]
    (= (mi/maintainability-index V G lloc loc cloc) (model V G lloc loc cloc))))

;; --- edge cases ----------------------------------------------------------

(law undefined-at-zero-volume
  (nil? (mi/maintainability-index 0.0 5 10 20 2)))

(law undefined-at-zero-lloc
  (nil? (mi/maintainability-index 100.0 5 0 20 2)))

(law no-lines-no-comment-weight
  (= (mi/comment-weight 0 10) 0.0))

;; a worked example: V=10 G=3 lloc=8 loc=10 cloc=2 -> MIwoc 72, comment weight
;; 31, MI 104
(law the-worked-example
  (= [(int (:maintainability-index-without-comments (mi/maintainability-index 10.0 3 8 10 2)))
      (int (:comment-weight (mi/maintainability-index 10.0 3 8 10 2)))
      (int (:maintainability-index (mi/maintainability-index 10.0 3 8 10 2)))]
     [72 31 104]))

;; --- the index is the sum of its terms -----------------------------------

(law the-index-is-the-sum-of-its-terms
  {:require :tested
   :because "the terms are logs, sines and square roots, which the prover does not model"}
  (forall [V Double, G Nat, lloc Nat, loc Nat, cloc Nat]
    (=> (and (pos? V) (pos? lloc))
        (= (:maintainability-index (mi/maintainability-index V G lloc loc cloc))
           (+ (:maintainability-index-without-comments (mi/maintainability-index V G lloc loc cloc))
              (:comment-weight (mi/maintainability-index V G lloc loc cloc)))))))
