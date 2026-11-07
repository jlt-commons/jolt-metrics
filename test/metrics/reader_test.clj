(ns metrics.reader-test
  "Behaviour tests for the Clojure reader adapter: forms, decision kinds, the
  cognitive tree, Halstead tokens and namespace imports."
  (:require [clojure.test :refer [deftest is]]
            [metrics.reader :as r]
            [metrics.cognitive :as cog]))

(deftest reads-all-top-level-forms
  (is (= 2 (count (r/read-forms "(defn f [x] x)\n(defn g [] 1)")))))

(deftest decision-kinds-if
  (is (= [:if] (r/decision-kinds '(if a b c)))))

(deftest decision-kinds-when
  (is (= [:if] (r/decision-kinds '(when a b c)))))

(deftest decision-kinds-logical
  (is (= [:logical] (r/decision-kinds '(and a b)))))

(deftest decision-kinds-nested
  (is (= [:loop :if] (r/decision-kinds '(for [x xs] (if x 1 2))))))

(deftest decision-kinds-cond-counts-clauses
  (is (= [:if :if] (r/decision-kinds '(cond a b c d))))
  (is (= [:if] (r/decision-kinds '(cond a b :else c)))))

(deftest decision-kinds-catch
  (is (= [:catch] (r/decision-kinds '(try x (catch Exception e y))))))

(deftest extracts-imports
  (is (= '[clojure.string clojure.set]
         (r/imports '[(ns foo (:require [clojure.string :as str] [clojure.set]))]))))

(deftest extracts-functions
  (let [fns (r/functions '[(defn f [x] (if x 1 2)) (def g 3)])]
    (is (= 1 (count fns)))
    (is (= "f" (:name (first fns))))
    (is (= '[(if x 1 2)] (:body (first fns))))))

(deftest flow-tree-if
  (is (= [:If [:Leaf] [:Leaf] [:Else [:Leaf]]]
         (r/flow-tree '(if a b c)))))

(deftest flow-tree-bare-if
  (is (= [:If [:Leaf] [:Leaf] [:Leaf]]
         (r/flow-tree '(if a b)))))

(deftest flow-tree-nest
  (is (= 1 (cog/complexity (r/flow-tree '(for [x xs] x))))))

(deftest halstead-tokens
  (is (= {:operators ["+"] :operands ["a" "b"]}
         (r/halstead-tokens '(+ a b)))))

(deftest form-count
  (is (= 1 (r/form-count '(if a b c))))
  (is (= 2 (r/form-count '(if (and a b) c d)))))

(deftest analyze-a-file
  (let [model (r/analyze-file "x.clj" "(ns x (:require [clojure.string]))\n(defn f [a b] (if a b c))\n(defn g [] 1)")]
    (is (= 2 (count (:functions model))))
    (is (= [:if] (:decision-kinds (first (:functions model)))))
    (is (= 'clojure.string (first (:imports model))))))
