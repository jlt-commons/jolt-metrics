(ns metrics.conformance-test
  "Conformance tests: worked examples pinning the expected metric values.

  Cyclomatic: else/try/switch/default are free; if, loops, case and catch
  count.  Cognitive follows the SonarSource rules, but a plain else stays free:
  if/when/cond/case/loops/catch add 1 plus nesting, try adds nothing, an else-if
  adds a flat 1, a logical operator run adds a flat 1, and fn raises nesting."
  (:require [clojure.test :refer [deftest is]]
            [metrics.reader :as r]
            [metrics.cyclomatic :as cyc]
            [metrics.cognitive :as cog]))

(defn- cc-of [form] (cyc/complexity (r/decision-kinds form)))

;; --- cyclomatic ----------------------------------------------------------

(deftest baseline-complexity-is-one
  ;; a function with no branches
  (is (= 1 (cyc/complexity []))))

(deftest a-single-if-is-two
  (is (= 2 (cc-of '(if a b c)))))

(deftest else-is-free
  ;; an if's else does not add a branch on its own
  (is (= [:if] (r/decision-kinds '(if a b c)))))

(deftest if-nested-if-else-if-is-four
  ;; if { if {} } else if {} else {}: baseline 1 + outer if + nested if + else-if;
  ;; the else is free
  (is (= 4 (cc-of '(if a (if b x) (if c y))))))

(deftest switch-is-free-but-clauses-count
  ;; cond: each non-default clause counts; the default is free
  (is (= [:if :if] (r/decision-kinds '(cond a b c d :else e))))
  (is (= 3 (cc-of '(cond a b c d :else e)))))

(deftest case-counts-except-default
  (is (= [:if :if] (r/decision-kinds '(case x 1 a 2 b))))
  (is (= 3 (cc-of '(case x 1 a 2 b)))))

(deftest loops-count
  (is (= [:loop] (r/decision-kinds '(doseq [i xs] x)))))

(deftest try-is-free-but-catch-counts
  (is (= [:catch] (r/decision-kinds '(try x (catch Exception e y)))))
  (is (= 2 (cc-of '(try x (catch Exception e y))))))

(deftest logical-operators-count
  (is (= [:logical] (r/decision-kinds '(and a b)))))

;; --- cognitive -----------------------------------------------------------

(deftest cognitive-simple-is-one
  ;; if/else -> 1
  (is (= 1 (cog/complexity (r/flow-tree '(if a b c))))))

(deftest cognitive-medium-is-four
  ;; for(+1) if-in-for(+2) try(0) catch(+1) -> 4
  (is (= 4
         (cog/complexity
          (r/flow-tree '(do (for [i (range 10)] (if (even? i) a b))
                            (try x (catch Exception e y))))))))

(deftest cognitive-try-is-free
  (is (= 0 (cog/complexity (r/flow-tree '(try x (finally y))))))
  (is (= 1 (cog/complexity (r/flow-tree '(try x (catch Exception e y)))))))

(deftest cognitive-nested-else-if-is-flat
  ;; for(+1) if-in-for(+2) else-if(+1) -> 4
  (is (= 4 (cog/complexity (r/flow-tree '(for [i x] (if a b (if c d e))))))))

(deftest cognitive-logical-runs-add-one
  (is (= 2 (cog/complexity (r/flow-tree '(if (and a b) c d)))))
  (is (= 2 (cog/complexity (r/flow-tree '(if (and a b c) d e)))))
  (is (= 3 (cog/complexity (r/flow-tree '(if (and a (or b c)) d e)))))
  (is (= 1 (cog/complexity (r/flow-tree '(if (and a) d e)))) "no operator to read"))

(deftest cognitive-a-closure-raises-nesting
  (is (= 2 (cog/complexity (r/flow-tree '(map (fn [x] (if x 1 2)) xs)))))
  (is (= 2 (cog/complexity (r/flow-tree '(letfn [(f [x] (if x 1 2))] (f 1))))))
  (is (= 2 (cog/complexity (r/flow-tree '(reify P (m [x] (if x 1 2))))))))

(deftest cognitive-cond-is-an-else-if-chain
  ;; the paper: an if-else-if chain "must be read carefully", while a switch "can
  ;; often be taken in at a glance", so each cond clause costs like an else-if
  (is (= 1 (cog/complexity (r/flow-tree '(cond a b)))))
  (is (= 2 (cog/complexity (r/flow-tree '(cond a b c d)))))
  (is (= 2 (cog/complexity (r/flow-tree '(cond a b c d :else e)))) "the default is a free else")
  (is (= 2 (cog/complexity (r/flow-tree '(cond a b c d true e)))))
  (is (= 4 (cog/complexity (r/flow-tree '(for [i x] (cond a b c d))))) "only the first clause pays for nesting")
  (is (= 3 (cog/complexity (r/flow-tree '(cond a (if x y))))) "a clause body is nested")
  (is (= 3 (cog/complexity (r/flow-tree '(cond a b :else (for [i x] i))))) "so is the default")
  (is (= 2 (cog/complexity (r/flow-tree '(cond a b :else (if x y))))) "an if right after else is an else-if")
  (is (= 3 (cog/complexity (r/flow-tree '(cond (if x y) b)))) "a test is nested too"))

(deftest cognitive-cond-does-not-deepen-nesting
  (is (= 1 (cog/max-nesting (r/flow-tree '(cond a b c d e f))))))

(deftest cognitive-case-and-condp-are-switches
  (is (= 1 (cog/complexity (r/flow-tree '(case x 1 a 2 b 3 c)))))
  (is (= 1 (cog/complexity (r/flow-tree '(condp = x 1 a 2 b 3 c))))))

(deftest cognitive-conditional-threading-is-a-series-of-ifs
  (is (= 1 (cog/complexity (r/flow-tree '(cond-> x a (f))))))
  (is (= 2 (cog/complexity (r/flow-tree '(cond-> x a (f) b (g))))))
  (is (= 2 (cog/complexity (r/flow-tree '(cond->> x a (f) b (g))))))
  (is (= 5 (cog/complexity (r/flow-tree '(for [i x] (cond-> i a (f) b (g))))))
      "the loop, then each clause at nesting 1")
  (is (= 2 (cog/complexity (r/flow-tree '(cond-> (if p q) a (f))))) "the threaded value is code"))

;; --- worked examples from the Cognitive Complexity paper -------------------

(deftest paper-my-method-is-nine
  ;; if +1, for +2, while +3, catch +1, if +2
  (is (= 9 (cog/complexity
            (r/flow-tree '(try (if c1 (for [i xs] (while c2 y)))
                               (catch Exception e (if c3 z))))))))

(deftest paper-lambda-is-two
  (is (= 2 (cog/complexity (r/flow-tree '(let [r (fn [] (if c1 y))] r))))))

(deftest paper-get-words-is-one
  (is (= 1 (cog/complexity
            (r/flow-tree '(case number 1 "one" 2 "a couple" 3 "a few" "lots"))))))

(deftest paper-sum-of-primes-without-its-labelled-jump-is-six
  ;; for +1, for +2, if +3; Clojure has no continue to a label for the paper's last +1
  (is (= 6 (cog/complexity
            (r/flow-tree '(for [i xs] (for [j ys] (if (zero? (mod i j)) i j))))))))

(deftest cognitive-nested-for-is-three
  ;; a loop containing an if: +1 + (1 + 1) = 3
  (is (= 3 (cog/complexity (r/flow-tree '(for [i xs] (if a b c)))))))
