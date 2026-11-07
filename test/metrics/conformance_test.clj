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

(deftest cognitive-conditional-threading-nests
  (is (= 1 (cog/complexity (r/flow-tree '(cond-> x a (f)))))))

(deftest cognitive-nested-for-is-three
  ;; a loop containing an if: +1 + (1 + 1) = 3
  (is (= 3 (cog/complexity (r/flow-tree '(for [i xs] (if a b c)))))))
