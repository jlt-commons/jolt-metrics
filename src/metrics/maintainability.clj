(ns metrics.maintainability
  "The Maintainability Index.

  MIwoc = 171 - 5.2*ln(V) - 0.23*G - 16.2*ln(lloc), scaled to 0..100 and
  clamped at 0; MIcw = 50*sin(sqrt(2.4*cloc/loc)) is the comment term; the index
  is their sum.  The index is undefined (nil) when the Halstead volume or the
  logical LOC is zero, rather than assigning a misleading zero."
  (:require [clojure.math :as math]))

(defn mi-without-comments
  "The comment-free part of the index, 0..100."
  [V G lloc]
  (max (* (- 171
             (* 5.2 (math/log (double V)))
             (* 0.23 G)
             (* 16.2 (math/log (double lloc))))
          (/ 100 171))
       0))

(defn comment-weight
  "The comment term, 50*sin(sqrt(2.4*cloc/loc)); 0 when there are no lines."
  [loc cloc]
  (if (pos? loc)
    (* 50 (math/sin (math/sqrt (* 2.4 (/ cloc loc)))))
    0.0))

(defn maintainability-index
  "The index from Halstead volume V, cyclomatic complexity G, logical LOC lloc,
  total LOC loc and comment LOC cloc.  nil when V <= 0 or lloc <= 0."
  [V G lloc loc cloc]
  (when (and (pos? V) (pos? lloc))
    (let [woc (mi-without-comments V G lloc)
          cw  (comment-weight loc cloc)]
      {:maintainability-index (+ woc cw)
       :maintainability-index-without-comments woc
       :comment-weight cw})))
