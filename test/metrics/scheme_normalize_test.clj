(ns metrics.scheme-normalize-test
  "Hand-scored Scheme: each construct against the numbers it should get once it has
  been normalized and run through the shared metrics."
  (:require [clojure.test :refer [deftest is testing]]
            [metrics.core :as core]
            [metrics.scheme.normalize :as n]
            [metrics.scheme.reader :as r]))

(defn- units [src] (:functions (core/analyze-source "x.ss" src)))
(defn- unit [src] (first (units src)))
(defn- scores [src] (let [u (unit src)] [(:cyclomatic u) (:cognitive u)]))
(defn- body
  "Scores of an expression, as the body of a function."
  [expr]
  (scores (str "(define (f x) " expr ")")))
(defn- norm1 [src] (first (n/normalize (r/read-forms src))))

;; --- definitions ---------------------------------------------------------

(deftest define-forms-are-functions
  (testing "name, kind and parameters"
    (is (= [["f" :function 2]] (map (juxt :name :kind :params) (units "(define (f a b) a)"))))
    (is (= [["f" :function 2]] (map (juxt :name :kind :params) (units "(define f (lambda (a b) a))"))))
    (is (= [["f" :function 2]] (map (juxt :name :kind :params) (units "(define (f a . rest) a)"))))
    (is (= [["f" :function 1]] (map (juxt :name :kind :params) (units "(define (f . rest) rest)"))))
    (is (= [["f" :function 1]] (map (juxt :name :kind :params) (units "(define f (lambda args args))"))))
    (is (= [["f" :function 2]] (map (juxt :name :kind :params) (units "(define f (case-lambda [(a) a] [(a b) a]))")))))
  (testing "a value is not a function"
    (is (empty? (units "(define x 1)")))
    (is (empty? (units "(define x (make-thing))")))))

(deftest gambit-optional-markers-are-not-parameters
  (is (= 2 (:params (unit "(define (f a #!optional b) a)")))))

(deftest curried-define
  (let [u (unit "(define ((f a) b) (if a b a))")]
    (is (= "f" (:name u)))
    (is (= 1 (:params u)))
    (is (= [2 2] [(:cyclomatic u) (:cognitive u)]) "the if sits inside the returned closure")))

(deftest a-let-over-lambda-is-a-function
  (let [u (unit "(define f (let ([cache 1]) (lambda (a) (if a cache 2))))")]
    (is (= "f" (:name u)))
    (is (= 1 (:params u)))
    (is (= 2 (:cyclomatic u)))))

(deftest case-lambda-reads-every-arity
  (is (= [3 2] (scores "(define f (case-lambda [(a) (if a 1 2)] [(a b) (if a b 2)]))"))))

(deftest macros-measure-their-templates
  (testing "one rule has no branch"
    (let [u (unit "(define-syntax m (syntax-rules () [(_ a) (if a 1 2)]))")]
      (is (= ["m" :macro] [(:name u) (:kind u)]))
      (is (= [2 1] [(:cyclomatic u) (:cognitive u)]))))
  (testing "each further rule is one, and patterns are not code"
    (is (= 2 (:cyclomatic (unit "(define-syntax m (syntax-rules () [(if a) 1] [(when b) 2]))")))))
  (testing "syntax-case, whose clauses can have a fender"
    (is (= 3 (:cyclomatic
              (unit "(define-syntax m (lambda (x) (syntax-case x () [(_ a) (if #'a 1 2)] [_ 2])))"))))
    (is (= 3 (:cyclomatic
              (unit "(define-syntax m (lambda (x) (syntax-case x () [(_ a) (when #'a #t) 1] [_ 2])))")))))
  (testing "gambit's define-macro"
    (is (= ["m" :macro 1] ((juxt :name :kind :params) (unit "(define-macro (m a) a)"))))))

(deftest containers-are-opened
  (is (= ["f" "g"] (map :name (units "(begin (define (f) 1) (define (g) 2))"))))
  (is (= ["f"] (map :name (units "(module m (f) (define (f) 1))"))))
  (is (= ["f"] (map :name (units "(module (f) (define (f) 1))"))))
  (is (= ["f"] (map :name (units "(library (a b) (export f) (import (chezscheme)) (define (f) 1))"))))
  (is (= ["f"] (map :name (units "(eval-when (compile) (define (f) 1))")))))

(deftest a-nested-define-is-a-closure
  (let [u (unit "(define (f a) (define (g b) (if b 1 2)) (g a))")]
    (is (= [1] (map :params [u])))
    (is (= [2 2 2] [(:cyclomatic u) (:cognitive u) (:nesting u)]))
    (is (= 1 (count (units "(define (f a) (define (g b) b) (g a))"))))))

;; --- control flow --------------------------------------------------------

(deftest if-when-unless
  (is (= [2 1] (body "(if x 1 2)")))
  (is (= [2 1] (body "(when x 1)")))
  (is (= [2 1] (body "(unless x 1)")))
  (is (= [3 2] (body "(if x 1 (if y 2 3))")) "an if directly in an else is an else-if")
  (is (= [3 3] (body "(if x (if y 2 3) 1)"))))

(deftest cond-is-a-chain
  (is (= [3 2] (body "(cond [x 1] [y 2])")))
  (is (= [3 2] (body "(cond [x 1] [y 2] [else 3])")) "else is free")
  (is (= [3 2] (body "(cond (x 1) (y 2) (#t 3))")) "so is #t")
  (is (= [2 1] (body "(cond [x] [else 2])")) "a clause with only a test")
  (is (= [2 1] (body "(cond [x => g] [else 2])")) "=> is not a value")
  (is (= [1 0] (body "(cond [else 2])")) "nothing left to decide"))

(deftest case-is-a-switch
  (is (= [3 1] (body "(case x [(1 2) a] [(3) b] [else c])")))
  (is (= [2 1] (body "(case x [(if when) a] [else c])")) "constants are not code")
  (is (= [3 3] (body "(case x [(1) (if y 1 2)] [else c])")) "a clause body is nested"))

(deftest logic-counts-per-operator
  (is (= [4 2] (body "(if (and x y z) 1 2)")) "two operators, one run")
  (is (= [4 3] (body "(if (and x (or y z)) 1 2)"))))

(deftest let-forms-do-not-branch
  (doseq [head ["let" "let*" "letrec" "letrec*" "parameterize" "fluid-let"]]
    (is (= [1 0] (body (str "(" head " ([a 1]) a)"))) head))
  (is (= [1 0] (body "(let-values ([(a b) (g)]) a)")))
  (is (= [1 0] (body "(let*-values ([(a b) (g)]) a)"))))

(deftest code-in-bindings-counts
  (is (= [2 1] (body "(let ([a (if x 1 2)]) a)")))
  (is (= [2 1] (body "(let-values ([(a b) (if x 1 2)]) a)"))))

(deftest binding-names-are-not-calls
  (is (= 1 (:lloc (unit "(define (f x) (let ([a x]) a))"))) "no list form but the let"))

(deftest named-let-is-a-loop
  (is (= [2 1] (body "(let lp ([i 0]) (lp (+ i 1)))")))
  (is (= [3 3] (body "(let lp ([i 0]) (when i (lp (+ i 1))))"))))

(deftest do-is-a-loop-with-a-test
  (is (= [3 3] (body "(do ([i 0 (+ i 1)]) ((> i 3) i) (g i))")) "the loop, then its exit test nested")
  (is (= [3 3] (body "(do ((i 0 (+ i 1))) ((> i 3)) (g i))"))))

(deftest guard-is-a-catch
  (is (= [2 1] (body "(guard (e [#t 1]) (g))")) "a catch-all is one decision")
  (is (= [2 1] (body "(guard (e [else 1]) (g))")))
  (is (= [3 3] (body "(guard (e [(string? e) 1] [else 2]) (g))")) "catch, then a clause inside it")
  (is (= [3 2] (body "(guard (e [#t 1]) (if x 1 2))")) "the body is not nested"))

(deftest closures-nest
  (is (= [2 2] (body "(map (lambda (a) (if a 1 2)) x)")))
  (is (= [2 2] (body "(g (case-lambda [(a) (if a 1 2)]))"))))

;; --- data and templates --------------------------------------------------

(deftest quoted-data-is-not-code
  (is (= [1 0] (body "'(if a b c)")))
  (is (= [1 0] (body "#(if a b c)"))))

(deftest quasiquote-holds-code-only-where-unquoted
  (is (= [1 0] (body "`(if a b c)")))
  (is (= [2 1] (body "`(a ,(if x 1 2))")))
  (is (= [2 1] (body "`(a ,@(if x 1 2))")))
  (is (= [2 1] (body "`(a (b ,(if x 1 2)))")) "at any depth"))

;; --- names that mean something else in Clojure ---------------------------

(deftest a-named-let-called-loop-is-not-a-clojure-loop
  (is (= [2 1] (body "(let loop ([i 0]) (loop (+ i 1)))")))
  ;; loop +1, cond +2, else-if +1, and +1, and the if alone in the else is an else-if +1
  (is (= [6 6] (scores "(define (f x y)
                          (let loop ([i 0])
                            (cond [(= i x) 'done]
                                  [(and (> i y) (odd? i)) (loop (+ i 2))]
                                  [else (if (even? i) (loop (+ i 1)) (begin (display i) (loop (+ i 1))))])))"))))

(deftest clojure-heads-mean-nothing-in-scheme
  (doseq [head ["for" "while" "if-not" "when-let" "condp" "cond->" "try" "catch" "fn" "defn" "comment"
                "dotimes" "doseq" "letfn" "reify" "defmethod"]]
    (is (= [1 0] (body (str "(" head " a b c)"))) head))
  (testing "and comment does not hide its arguments"
    (is (= [2 1] (body "(comment (if x 1 2))")))))

(deftest a-definer-named-like-a-clojure-one-defines-nothing
  (is (empty? (units "(defn f [x] x)")))
  (is (empty? (units "(deftest t 1)"))))

(deftest normalized-forms-keep-their-position
  (is (= 2 (:line (meta (norm1 "\n(define (f) 1)")))))
  (is (= 3 (:column (meta (norm1 "  (define (f) 1)"))))))

;; --- extents and lines ----------------------------------------------------

(deftest a-definition-knows-its-extent
  (let [[f g] (units "\n(define (f a)\n  ;; why\n  (g a))\n\n(define (g) 1)")]
    (is (= [2 4] [(:line f) (:end-line f)]))
    (is (= {:loc 3 :sloc 2 :cloc 1 :blank 0} (:locs f)))
    (is (= [6 6] [(:line g) (:end-line g)]))))

(deftest a-bracketed-top-level-definition-knows-its-extent
  (is (= [1 2] ((juxt :line :end-line) (unit "[define (f a)\n  a]")))))

;; --- the file ------------------------------------------------------------

(deftest the-language-is-recorded
  (is (= :scheme (:language (core/analyze-source "x.ss" "(define (f) 1)"))))
  (is (= :clojure (:language (core/analyze-source "x.clj" "(defn f [] 1)")))))

(deftest top-level-code-counts-at-file-level
  (let [file (core/analyze-source "x.ss" "(define (f x) (if x 1 2)) (guard (e [#t 1]) (when y 2))")]
    (is (= 4 (:cyclomatic file)))))

(deftest every-source-extension-is-scheme
  (doseq [ext ["ss" "scm" "sls" "sps"]]
    (is (= :scheme (:language (core/analyze-source (str "x." ext) "(define (f) 1)"))) ext)))

(deftest broken-scheme-is-reported-not-fatal
  (let [result (core/analyze-files [["a.ss" "(define (f) 1)"] ["b.ss" "(define ("]])]
    (is (= ["a.ss"] (map :path (:files result))))
    (is (= ["b.ss"] (map :path (:errors result))))))

(deftest a-mixed-project-gets-one-report
  (let [result (core/analyze-files [["a.ss" "(define (f x) (if x 1 2))"]
                                    ["b.clj" "(ns b) (defn g [x] (if x 1 2))"]])]
    (is (= 2 (:functions (:summary result))))
    (is (= #{:scheme :clojure} (set (map :language (:files result)))))))

(deftest public-functions-are-not-called-unused-in-scheme
  ;; a Scheme runtime's names are reached from emitted code and strings
  (let [{:keys [unused-public]} (:cleanup (core/analyze-files
                                           [["a.ss" "(define (dead) 1)"]
                                            ["b.clj" "(ns b) (defn dead-too [] 1)"]]))]
    (is (= ["dead-too"] (map :name unused-public)))))

(deftest scheme-names-can-still-keep-clojure-ones-alive
  (let [{:keys [unused-public]} (:cleanup (core/analyze-files
                                           [["a.ss" "(define (f) (shared))"]
                                            ["b.clj" "(ns b) (defn shared [] 1)"]]))]
    (is (empty? unused-public))))

;; --- real code has shapes the textbook doesn't ----------------------------

(deftest a-form-can-normalize-to-a-bare-value
  (is (= [1 0] (body "(cond [else #f])")))
  (is (= [1 0] (body "(cond [else 1])")))
  (is (= [1 0] (body "(cond [#t \"x\"])")))
  (is (= [1 0] (body "(begin)"))))

(deftest guard-clauses-need-not-be-lists
  (is (= [2 1] (body "(guard (e x) (g))"))))

(deftest a-malformed-form-is-just-a-call
  ;; macro templates and data-driven code hold shapes the translators don't expect
  (doseq [odd ["(do x)" "(do 1 2)" "(case x y)" "(guard e (g))" "(lambda)" "(let)" "(let*)"
               "(case-lambda x)" "(syntax-rules x y)" "(syntax-case x y z)" "(cond)"
               "(cond x)" "(define)" "(quasiquote)"]]
    (is (= 1 (:cyclomatic (unit (str "(define (f x) " odd ")")))) odd)))

(deftest a-malformed-top-level-form-is-not-fatal
  (doseq [odd ["(define)" "(define-syntax)" "(define-macro)" "(library)" "(module)" "(eval-when)" "(begin)"]]
    (is (empty? (:errors (core/analyze-files [["a.ss" (str "(define (ok) 1)\n" odd)]]))) odd)))

(deftest a-case-keeps-its-branches-when-a-clause-is-fine
  (is (= [3 1] (body "(case x [(1) a] [(2) b] [else #f])"))))
