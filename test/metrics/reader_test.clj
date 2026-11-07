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

;; --- imports -------------------------------------------------------------

(deftest prefix-lists-expand
  (is (= '[clojure.string clojure.set x.y x.z]
         (r/imports '[(ns a (:require [clojure [string :as s] [set]] (x y z)))]))))

(deftest nested-prefix-lists-expand
  (is (= '[a.b.c] (r/imports '[(ns z (:require [a [b [c :as d]]]))]))))

(deftest a-libspec-with-options-is-not-a-prefix
  (is (= '[b.c] (r/imports '[(ns a (:use [b.c :only [d]]))]))))

(deftest import-names-the-package
  (is (= '[java.util java.io foo.Bar]
         (r/imports '[(ns a (:import [java.util Date] (java.io File) foo.Bar))]))))

(deftest macro-requires-count
  (is (= '[b.c] (r/imports '[(ns a (:require-macros [b.c :as d]))])))
  (is (= '[b.c] (r/imports '[(ns a (:use-macros [b.c :only [d]]))]))))

(deftest refer-clojure-is-not-a-dependency
  (is (= [] (r/imports '[(ns a (:refer-clojure :exclude [map]))]))))

(deftest top-level-require-counts
  (is (= '[b.c e f.g] (r/imports '[(require '[b.c :as d] 'e :reload)
                                   (use '[f.g])
                                   (require dynamic)])))
  (is (= '[a b] (r/imports '[(ns a2 (:require [a])) (do (require 'b))]))))

(deftest string-libspecs-are-external
  (is (= [] (r/imports '[(ns a (:require ["react" :as r]))]))))
