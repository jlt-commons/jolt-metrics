(ns metrics.scheme-reader-test
  "Reading Scheme text into forms, without ever evaluating it."
  (:require [clojure.test :refer [deftest is]]
            [metrics.scheme.reader :as r]))

(defn- read1 [s] (first (r/read-forms s)))

(deftest reads-every-top-level-form
  (is (= 2 (count (r/read-forms "(a) (b)")))))

(deftest brackets-read-as-lists
  (is (= '(let ((a b)) a) (read1 "(let ([a b]) a)")))
  (is (seq? (second (read1 "(f [a])")))))

(deftest slashes-stay-in-the-name
  (let [s (read1 "call/cc")]
    (is (= "call/cc" (name s)))
    (is (nil? (namespace s)))))

(deftest booleans-are-values-but-f-is-a-name
  (is (true? (read1 "#t")))
  (is (true? (read1 "#true")))
  (is (false? (read1 "#f")))
  (is (false? (read1 "#false")))
  (is (symbol? (read1 "f")))
  (is (symbol? (read1 "t"))))

(deftest strings-keep-their-text
  (is (= "host/chez/rt.ss" (read1 "\"host/chez/rt.ss\""))))

(deftest quoting-forms
  (is (= '(quote a) (read1 "'a")))
  (is (= '(quasiquote (a (unquote b) (unquote-splicing c))) (read1 "`(a ,b ,@c)")))
  (is (= '(syntax a) (read1 "#'a")))
  (is (= '(quasisyntax (a (unsyntax b) (unsyntax-splicing c))) (read1 "#`(a #,b #,@c)"))))

(deftest vector-literals-are-data
  (is (= '(quote (a b)) (read1 "#(a b)")))
  (is (= '(quote (a b)) (read1 "#vu8(a b)"))))

(deftest comments-disappear
  (is (= '(a c) (read1 "(a ;; b\n c)")))
  (is (= '(a c) (read1 "(a #| b |# c)")))
  (is (= '(a c) (read1 "(a #;(b d) c)")))
  (is (= [] (r/read-forms ";; only a comment\n#| and a block |#")))
  (is (= [] (r/read-forms "#!chezscheme"))))

(deftest characters-and-prims-are-atoms
  (is (= 3 (count (read1 "(f #\\( #\\a)"))))
  (is (= "#%car" (name (first (read1 "(#%car x)")))))
  (is (= "##include" (name (first (read1 "(##include \"x\")")))))
  (is (= "#!void" (name (read1 "#!void")))))

(deftest lists-know-where-they-start
  (is (= {:line 2 :column 3} (select-keys (meta (read1 "\n  (a)")) [:line :column])))
  (is (= {:line 1 :column 4}
         (select-keys (meta (second (read1 "(a [b])"))) [:line :column]))))

(deftest empty-lists-read
  (is (= '(f ()) (read1 "(f ())"))))

(deftest unbalanced-source-is-an-error-with-its-line
  (is (thrown? Exception (r/read-forms "(a")))
  (is (thrown? Exception (r/read-forms "a)"))))

(deftest reading-evaluates-nothing
  (is (= 1 (count (r/read-forms "(define x #=(launch-missiles))")))))
