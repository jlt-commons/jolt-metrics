(ns metrics.hotspot
  "Hotspots: code that is both complex and changing is where a refactor pays off.

  Churn and complexity are normalized against a scale, and risk is how close a
  file sits to the corner where both are highest: 1 minus the distance to it,
  clamped to 0..1.  This is the measure ast-metrics uses, whose scale is the
  project's maxima.  metrics.core uses the maxima or a floor, whichever is
  higher: against the maxima alone the top file of any project is at the corner,
  however few its changes, and in a young project every commit to it, a refactor
  included, keeps it there."
  (:require [clojure.math :as math]))

(defn- clamp
  "x limited to 0..1."
  [x]
  (max 0.0 (min 1.0 x)))

(defn- gap
  "How far v falls short of top, as a fraction of top; 0 when top is 0."
  [top v]
  (if (pos? top)
    (clamp (/ (- top v) (double top)))
    0.0))

(defn risk
  "A file's risk from the scale's changes and complexity, and its own."
  [max-changes max-complexity changes complexity]
  (let [h (gap max-changes changes)
        v (gap max-complexity complexity)]
    (clamp (- 1.0 (math/sqrt (+ (* h h) (* v v)))))))
