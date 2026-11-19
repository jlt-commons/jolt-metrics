(ns metrics.lint-test
  (:require [clojure.test :refer [deftest is testing]]
            [metrics.reader :as reader]
            [metrics.lint :as lint]))

(defn- found [source]
  (lint/findings (reader/read-forms source)))

(defn- rules [source]
  (set (map :rule (found source))))

(defn- flags? [rule source]
  (contains? (rules source) rule))

(deftest clean-code-has-no-findings-at-default-levels
  (is (empty? (lint/with-levels (found "(ns a (:require [b :as c]))
(defn f \"Add one.\" [x] (let [y (inc x)] (when (pos? y) (println y) y)))
(defn g [m] (cond (:a m) 1 :else 2))
(defn h [xs] (loop [xs xs] (if (seq xs) (recur (rest xs)) :done)))
(def ^:dynamic *depth* 0)
(defn k [x] (try (f x) (catch Exception _ nil)))
'(do x)
(comment (do x))") nil))))

(deftest positions
  (let [[f] (filter #(= :redundant-do (:rule %))
                   (found "(ns a)\n(defn f [x]\n  (do (g x)))"))]
    (is (= {:rule :redundant-do :line 3 :column 3} (select-keys f [:rule :line :column])))
    (is (string? (:message f)))))

(deftest redundant-do
  (is (flags? :redundant-do "(do x)"))
  (is (flags? :redundant-do "(do)"))
  (is (flags? :redundant-do "(let [a 1] (do (f a) a))"))
  (is (flags? :redundant-do "(defn f [] (do (g) 1))"))
  (is (flags? :redundant-do "(fn [] (do (g) 1))"))
  (is (flags? :redundant-do "(when x (do (g) 1))"))
  (is (not (flags? :redundant-do "(if x (do (g) 1) 2)")))
  (is (not (flags? :redundant-do "#(do [%1 %2])"))))

(deftest redundant-let
  (is (flags? :redundant-let "(let [a 1] (let [b 2] (+ a b)))"))
  (is (flags? :redundant-let "(let [] 1)"))
  (is (not (flags? :redundant-let "(let [a 1] (f a) (let [b 2] b))"))))

(deftest redundant-let-binding
  (is (flags? :redundant-let-binding "(let [x x] x)"))
  (is (not (flags? :redundant-let-binding "(let [x y] x)"))))

(deftest cond-rules
  (is (flags? :cond-else "(cond a 1 :default 2)"))
  (is (not (flags? :cond-else "(cond a 1 :else 2)")))
  (is (flags? :constant-condition "(cond a 1 :else 2 b 3)")))

(deftest if-rules
  (is (flags? :missing-else-branch "(if x 1)"))
  (is (not (flags? :missing-else-branch "(if x 1 2)")))
  (is (flags? :if-nil-return "(if x 1 nil)"))
  (is (flags? :if-nil-return "(if x nil 1)"))
  (is (flags? :if-x-x-y "(if x x 1)"))
  (is (not (flags? :if-x-x-y "(if (f) (f) 1)"))))

(deftest missing-bodies
  (is (flags? :missing-body-in-when "(when x)"))
  (is (flags? :missing-clause-in-try "(try (f))"))
  (is (not (flags? :missing-clause-in-try "(try (f) (finally (g)))"))))

(deftest single-operands
  (is (flags? :single-operand-comparison "(= x)"))
  (is (flags? :single-operand-comparison "(not= x)"))
  (is (flags? :single-logical-operand "(and x)"))
  (is (not (flags? :single-logical-operand "(and x y)"))))

(deftest redundant-calls
  (is (flags? :redundant-nested-call "(+ a (+ b c))"))
  (is (not (flags? :redundant-nested-call "(+ a (- b c))")))
  (is (flags? :redundant-str-call "(str \"a\")"))
  (is (not (flags? :redundant-str-call "(str x)")))
  (is (flags? :redundant-call "(merge m)"))
  (is (flags? :redundant-call "(-> x)"))
  (is (not (flags? :redundant-call "(merge m n)")))
  (is (flags? :redundant-fn-wrapper "#(f %)"))
  (is (flags? :redundant-fn-wrapper "(fn [a b] (f a b))"))
  (is (not (flags? :redundant-fn-wrapper "#(f % 1)")))
  (is (not (flags? :redundant-fn-wrapper "#(.m %)"))))

(deftest equality-and-arithmetic
  (is (flags? :equals-true "(= x true)"))
  (is (flags? :equals-false "(= false x)"))
  (is (flags? :equals-nil "(= x nil)"))
  (is (flags? :plus-one "(+ x 1)"))
  (is (flags? :plus-one "(+ 1 x)"))
  (is (flags? :minus-one "(- x 1)"))
  (is (not (flags? :minus-one "(- 1 x)")))
  (is (flags? :not-empty? "(not (empty? xs))"))
  (is (flags? :single-key-in "(get-in m [:a])"))
  (is (not (flags? :single-key-in "(get-in m [:a :b])"))))

(deftest definitions
  (is (flags? :inline-def "(defn f [] (def x 1))"))
  (is (not (flags? :inline-def "(def x 1)")))
  (is (flags? :loop-without-recur "(loop [a 1] a)"))
  (is (flags? :case-duplicate-test "(case x 1 :a 1 :b :c)"))
  (is (flags? :case-duplicate-test "(case x (1 2) :a 2 :b)"))
  (is (flags? :earmuffed-var-not-dynamic "(def *x* 1)"))
  (is (flags? :dynamic-var-not-earmuffed "(def ^:dynamic x 1)"))
  (is (flags? :def-fn "(def f (fn [] 1))")))

(deftest docstrings
  (is (flags? :missing-docstring "(defn f [] 1)"))
  (is (not (flags? :missing-docstring "(defn- f [] 1)")))
  (is (not (flags? :missing-docstring "(defn ^:private f [] 1)")))
  (is (flags? :docstring-blank "(defn f \"  \" [] 1)"))
  (is (flags? :docstring-no-summary "(defn f \"does a thing\" [] 1)"))
  (is (not (flags? :docstring-no-summary "(defn f \"Does a thing.\n  more\" [] 1)")))
  (is (flags? :docstring-leading-trailing-whitespace "(defn f \" Do it. \" [] 1)"))
  (is (flags? :misplaced-docstring "(defn f [] \"Do it.\" 1)"))
  (is (not (flags? :misplaced-docstring "(defn f [] \"just a value\")"))))

(deftest namespace-rules
  (is (flags? :refer-all "(ns a (:require [b :refer :all]))"))
  (is (flags? :use "(ns a (:use b))"))
  (is (flags? :unsorted-required-namespaces "(ns a (:require [c] [b]))"))
  (is (not (flags? :unsorted-required-namespaces "(ns a (:require [b] [c]))"))))

(deftest data-is-not-code
  (testing "quoted forms and comment blocks are not linted"
    (is (empty? (found "'(if x 1)")))
    (is (empty? (found "(comment (if x 1))")))))

(deftest levels
  (let [fs (found "(do x)\n(+ x 1)")]
    (testing "kondo's defaults: redundant-do warns, plus-one is off"
      (is (= [[:redundant-do :warning]]
             (map (juxt :rule :level) (lint/with-levels fs nil)))))
    (testing "a project can turn a rule on, off or change its level"
      (is (= #{[:redundant-do :error] [:plus-one :info]}
             (set (map (juxt :rule :level)
                       (lint/with-levels fs {:redundant-do {:level :error}
                                             :plus-one {:level :info}})))))
      (is (empty? (lint/with-levels fs {:redundant-do {:level :off}}))))))

(deftest linter-config-problems
  (is (nil? (lint/config-problem nil)))
  (is (nil? (lint/config-problem {:redundant-do {:level :off}})))
  (is (string? (lint/config-problem {:no-such-rule {:level :off}})))
  (is (string? (lint/config-problem {:redundant-do {:level :loud}})))
  (is (string? (lint/config-problem [:redundant-do]))))
