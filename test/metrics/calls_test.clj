(ns metrics.calls-test
  "Fan-in, fan-out and reach, from the call graph jolt-lsp's index resolves over
  the files the analyzer collected."
  (:require [clojure.test :refer [deftest is testing]]
            [clojure.string :as str]
            [metrics.core :as core]
            [metrics.json :as json]
            [metrics.report :as report]))

(def ^:private project
  ;; c <- b <- a, and a passes c on as a value; d is only called by a test
  [["src/p/c.clj" "(ns p.c) (defn c [x] (inc x)) (defn d [x] (c (c x)))"]
   ["src/p/b.clj" "(ns p.b (:require [p.c :as c])) (defn b [x] (c/c x))"]
   ["src/p/a.clj" (str "(ns p.a (:require [p.b :refer [b]] [p.c :as c]))\n"
                       "(def table {:inc c/c})\n"
                       "(defn a [x] (b x))\n"
                       "(defn- helper [x] (a x))")]
   ["test/p/c_test.clj" "(ns p.c-test (:require [p.c :as c])) (deftest t (c/d 1))"]])

(defn- fn-named [result path fname]
  (->> (:files result)
       (filter #(= path (:path %)))
       first
       :functions
       (filter #(= fname (:name %)))
       first))

(deftest functions-carry-their-place-in-the-call-graph
  (let [r (core/analyze-files project)
        c (fn-named r "src/p/c.clj" "c")]
    (testing "c is called by d and b, and referred to by table"
      (is (= 3 (:fan-in c)))
      (is (= 0 (:fan-out c))))
    (testing "everything that reaches c at any distance: d, b, table, a, helper"
      (is (= 5 (:reach c))))
    (testing "recursion and repeat calls count once"
      (is (= 1 (:fan-out (fn-named r "src/p/c.clj" "d")))))
    (testing "a referred name resolves across files"
      (is (= 1 (:fan-in (fn-named r "src/p/b.clj" "b"))))
      (is (= 1 (:fan-out (fn-named r "src/p/a.clj" "a")))))))

(deftest tests-do-not-count-as-callers
  (let [d (fn-named (core/analyze-files project) "src/p/c.clj" "d")]
    (is (= 0 (:fan-in d)))
    (is (= 0 (:reach d)))))

(deftest only-clojure-gets-call-graph-numbers
  (let [r (core/analyze-files [["x.ss" "(define (f x) (g x)) (define (g x) x)"]])
        f (fn-named r "x.ss" "f")]
    (is (some? f))
    (is (nil? (:fan-in f)))
    (is (nil? (:reach f)))))

(deftest a-function-nothing-refers-to-has-none
  (let [r (core/analyze-files [["a.clj" "(ns a) (defn f [x] x)"]])]
    (is (empty? (:errors r)))
    (is (= 0 (:fan-in (fn-named r "a.clj" "f"))))))

(defn- branchy [fname n]
  (str "(defn " fname " [x]\n  (cond\n"
       (apply str (for [i (range n)] (str "    (= x " i ") (inc x)\n")))
       "    :else nil))\n"))

(deftest reach-raises-a-queued-function
  (let [src (str "(ns a)\n" (branchy "lonely" 12) (branchy "shared" 12)
                 (apply str (for [i (range 20)] (str "(defn u" i " [x] (shared x))\n"))))
        q (:queue (core/analyze-files [["a.clj" src]]))
        score #(:score (first (filter (fn [e] (= % (:name e))) q)))]
    (is (> (score "shared") (score "lonely")))
    (is (= "shared" (:name (first q))))
    (is (= 20 (get-in (first q) [:metrics :reach])))))

(deftest reports-show-reach
  (let [src (str "(ns a)\n" (branchy "shared" 12) "(defn u [x] (shared x))\n")
        r (core/analyze-files [["a.clj" src]])]
    (is (str/includes? (report/render r {:top 5}) "reach 1"))
    (is (str/includes? (json/render r) "\"reach\":1"))
    (is (str/includes? (json/render r) "\"fan_in\":1"))))

(deftest mutual-recursion-reaches-each-other-once
  (let [r (core/analyze-files [["a.clj" "(ns a) (defn e [x] (g x)) (defn g [x] (e x)) (defn h [x] (e x))"]])]
    (is (= 2 (:reach (fn-named r "a.clj" "e"))))
    (is (= 2 (:reach (fn-named r "a.clj" "g"))))
    (is (= 0 (:reach (fn-named r "a.clj" "h"))))))
