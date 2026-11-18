(ns metrics.egraph-test
  "Behaviour tests for the e-graph: hash-consing, union-find, and congruence
  restored by rebuild."
  (:require [clojure.test :refer [deftest is]]
            [metrics.egraph :as eg]))

(defn- leaf [s] [[:sym s]])

(deftest identical-terms-share-a-class
  (let [[g a] (eg/add-term eg/empty-graph [[:list 2] (leaf "inc") (leaf "x")])
        [g b] (eg/add-term g [[:list 2] (leaf "inc") (leaf "x")])]
    (is (= (eg/find g a) (eg/find g b)))
    (is (= 2 (eg/occurrences g a)))))

(deftest different-terms-get-different-classes
  (let [[g a] (eg/add-term eg/empty-graph (leaf "a"))
        [g b] (eg/add-term g (leaf "b"))]
    (is (not= (eg/find g a) (eg/find g b)))))

(deftest union-merges-and-sums-occurrences
  (let [[g a] (eg/add-term eg/empty-graph (leaf "a"))
        [g b] (eg/add-term g (leaf "b"))
        g (eg/rebuild (eg/union g a b))]
    (is (= (eg/find g a) (eg/find g b)))
    (is (= 2 (eg/occurrences g a)))
    (is (= 2 (count (eg/nodes g a))))))

(deftest rebuild-restores-congruence
  ;; a = b implies (f a) = (f b)
  (let [[g a] (eg/add-term eg/empty-graph (leaf "a"))
        [g b] (eg/add-term g (leaf "b"))
        [g fa] (eg/add-term g [[:list 2] (leaf "f") (leaf "a")])
        [g fb] (eg/add-term g [[:list 2] (leaf "f") (leaf "b")])
        g (eg/rebuild (eg/union g a b))]
    (is (= (eg/find g fa) (eg/find g fb)))
    (is (= 2 (eg/occurrences g fa)))))

(deftest add-node-with-class-children
  (let [[g x] (eg/add-term eg/empty-graph (leaf "x"))
        [g f] (eg/add-term g (leaf "f"))
        [g c] (eg/add-derived g [[:list 2] [:class f] [:class x]])
        _ (is (= 0 (eg/occurrences g c)) "a derived term is not in the source")
        [g d] (eg/add-term g [[:list 2] (leaf "f") (leaf "x")])]
    (is (= (eg/find g c) (eg/find g d)))
    (is (= 1 (eg/occurrences g c)))))

(deftest local-taint-propagates-upward
  (let [[g c] (eg/add-term eg/empty-graph [[:list 2] (leaf "inc") [[:local "x"]]])
        [g d] (eg/add-term g [[:list 2] (leaf "inc") [[:lit "1"]]])]
    (is (eg/local? g c))
    (is (not (eg/local? g d)))))

(deftest smallest-term-extracts-the-cheapest-form
  (let [[g big] (eg/add-term eg/empty-graph [[:list 3] (leaf "if") (leaf "c") (leaf "a")])
        [g small] (eg/add-term g (leaf "z"))
        g (eg/rebuild (eg/union g big small))]
    (is (= [[:sym "z"]] (eg/best-term g big)))))
