(ns metrics.halstead
  "Halstead complexity metrics.

  `operators` and `operands` are sequences of names; each occurrence of a name
  is one operator or operand.  The distinct counts are n1 and n2, the totals N1
  and N2; the derived values are the standard Halstead volume, difficulty,
  effort, time and estimated-bug count."
  (:require [clojure.math :as math]))

(defn- log2
  "Base-2 logarithm; log2 1 is 0."
  [x]
  (/ (math/log (double x)) (math/log 2.0)))

(defn calculate
  "Halstead metrics of a scope from its operator and operand occurrences."
  [operators operands]
  (let [n1 (count (distinct operators))
        n2 (count (distinct operands))
        N1 (count operators)
        N2 (count operands)
        n  (+ n1 n2)
        N  (+ N1 N2)
        ;; estimated length N^ = n1 log2 n1 + n2 log2 n2; 0 when either distinct
        ;; count is 0 (the log is then undefined)
        hat-n (if (and (pos? n1) (pos? n2))
                (+ (* n1 (log2 n1)) (* n2 (log2 n2)))
                0.0)
        V (if (pos? n) (* N (log2 n)) 0.0)
        D (if (pos? n2) (* (/ n1 2.0) (/ N2 n2)) 0.0)
        E (* D V)
        T (/ E 18.0)
        bugs (if (pos? V) (/ V 3000.0) 0.0)]
    {:n1 n1 :n2 n2 :N1 N1 :N2 N2
     :vocabulary n :length N
     :estimated-length hat-n
     :volume V :difficulty D :effort E :time T :bugs bugs}))
