(ns metrics.loc
  "Line-based LOC for Clojure source.

  Clojure has only line comments (`;` to end of line) and no block comments, so
  a comment line is one whose first non-blank character is `;`.  sloc is every
  non-blank, non-comment line."
  (:require [clojure.string :as str]))

(defn count-lines
  "Classify the lines of source into {:loc :sloc :cloc :blank}."
  [source]
  (reduce
   (fn [acc line]
     (let [s (str/trim line)
           acc (update acc :loc inc)]
       (cond
         (empty? s)  (update acc :blank inc)
         (str/starts-with? s ";") (update acc :cloc inc)
         :else (update acc :sloc inc))))
   {:loc 0 :sloc 0 :cloc 0 :blank 0}
   (str/split-lines source)))
