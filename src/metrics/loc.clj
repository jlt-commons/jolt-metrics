(ns metrics.loc
  "LOC for Clojure source.

  A line is source if it holds code, comment if all it holds is a `;` comment, a
  docstring or a `#_` discard, and blank otherwise.  A string that is not a
  docstring is code on every line it spans, whatever it contains."
  (:require [metrics.scanner :as scanner]))

(defn tally
  "Count a sequence of line classes into {:loc :sloc :cloc :blank}."
  [classes]
  (reduce
   (fn [acc line-class]
     (let [acc (update acc :loc inc)]
       (case line-class
         :blank (update acc :blank inc)
         :comment (update acc :cloc inc)
         (update acc :sloc inc))))
   {:loc 0 :sloc 0 :cloc 0 :blank 0}
   classes))

(defn count-lines
  "Classify the lines of source into {:loc :sloc :cloc :blank}."
  [source]
  (tally (scanner/line-classes source)))
