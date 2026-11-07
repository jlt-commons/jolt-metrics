(ns metrics.scheme-scanner-test
  "Line classes and list extents for Scheme source."
  (:require [clojure.test :refer [deftest is]]
            [metrics.loc :as loc]
            [metrics.scheme.scanner :as scanner]))

(defn- counts [src] (loc/tally (:classes (scanner/scan src))))

(deftest code-and-line-comments
  (is (= {:loc 3 :sloc 1 :cloc 1 :blank 1} (counts ";; why\n\n(f x)"))))

(deftest a-trailing-comment-is-source
  (is (= {:loc 1 :sloc 1 :cloc 0 :blank 0} (counts "(f x) ; why"))))

(deftest block-comments-are-comments
  (is (= {:loc 3 :sloc 1 :cloc 2 :blank 0} (counts "#| one\n  two |#\n(f)"))))

(deftest block-comments-nest
  (is (= {:loc 3 :sloc 1 :cloc 2 :blank 0} (counts "#| a #| b |#\n c |#\n(f)"))))

(deftest datum-comments-are-comments
  (is (= {:loc 3 :sloc 1 :cloc 2 :blank 0} (counts "#;(a\n b)\n(c)"))))

(deftest a-string-is-source-whatever-it-holds
  (is (= {:loc 3 :sloc 3 :cloc 0 :blank 0} (counts "(f \"a\n;; not comment\nb\")"))))

(deftest character-literals-open-nothing
  (is (= 2 (:sloc (counts "(f #\\( #\\; #\\\" #\\[)\n(g)"))))
  (is (= [[1 1] [2 1]] (sort (keys (:extents (scanner/scan "(f #\\( #\\) #\\space)\n(g)")))))))

(deftest brackets-are-parens
  (is (= {[1 1] 3, [1 6] 2, [1 7] 1, [2 7] 2}
         (:extents (scanner/scan "(let ([a 1]\n      [b 2])\n  a)")))))

(deftest extents-cover-a-bracketed-list
  (is (= 2 (get (:extents (scanner/scan "[a\n b]")) [1 1]))))

(deftest a-comma-is-not-whitespace
  ;; the unquote is a token, so columns after it still line up
  (is (= 2 (get (:extents (scanner/scan "`(a ,(b\n c))")) [1 6]))))

(deftest directives-and-shebangs-are-comments
  (is (= 1 (:cloc (counts "#!chezscheme\n(f)"))))
  (is (= 1 (:cloc (counts "#!/usr/bin/env scheme\n(f)")))))

(deftest gambit-hash-bang-objects-are-code
  ;; #!void must not swallow the rest of the line, closing parens included
  (is (= 2 (get (:extents (scanner/scan "(f #!void\n a)")) [1 1])))
  (is (= [[1 1]] (keys (:extents (scanner/scan "(f #!void)")))))
  (is (= [[1 1]] (keys (:extents (scanner/scan "(f #!eof)"))))))

(deftest it-is-total-on-any-text
  (is (map? (scanner/scan "(((")))
  (is (map? (scanner/scan ")))")))
  (is (map? (scanner/scan "\"unterminated")))
  (is (map? (scanner/scan "#| unterminated")))
  (is (map? (scanner/scan "#")))
  (is (map? (scanner/scan "#\\"))))
