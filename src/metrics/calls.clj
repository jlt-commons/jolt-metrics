(ns metrics.calls
  "How a function sits in the call graph.

  An edge [caller callee] says caller refers to callee, by calling it or by
  passing it on.  Fan-in is how many distinct functions refer to a function and
  fan-out how many it refers to; recursion counts for neither.  Reach, the
  functions that refer to it at any distance, is what a change to it can break,
  and the reach factor weighs a queue entry by it."
  (:require [clojure.math :as math]))

(def reach-cap
  "The reach past which a function counts as depended on by everything."
  100)

(defn- others
  "The distinct edges that are not a function referring to itself."
  [edges]
  (distinct (remove (fn [[from to]] (= from to)) edges)))

(defn fan-in
  "{callee n}: how many distinct functions refer to each function."
  [edges]
  (frequencies (map second (others edges))))

(defn fan-out
  "{caller n}: how many distinct functions each function refers to."
  [edges]
  (frequencies (map first (others edges))))

(defn reach-factor
  "A queue weight from a function's reach: 1 with nothing depending on it, rising
  on a log scale to 2 at reach-cap and no further."
  [reach]
  (+ 1.0 (/ (math/log (+ 1.0 (min reach reach-cap)))
            (math/log (+ 1.0 reach-cap)))))
