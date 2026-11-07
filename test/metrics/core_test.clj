(ns metrics.core-test
  "Project-level behaviour: nothing a single file does may take the run down,
  and the rollups only average what is defined."
  (:require [clojure.test :refer [deftest is testing]]
            [metrics.core :as core]
            [metrics.reader :as r]
            [metrics.report :as report]))

(def ran (atom false))

(deftest reading-never-executes-the-source
  (reset! ran false)
  (let [src "(ns evil) (defn f [] #=(clojure.core/reset! metrics.core-test/ran true))"
        result (core/analyze-files [["evil.clj" src]])]
    (is (false? @ran))
    (is (= ["evil.clj"] (map :path (:errors result))))))

(deftest auto-resolved-keywords-with-an-alias-read
  (testing "the alias comes from the ns form"
    (is (= 1 (count (r/read-forms "(defn f [] ::d/x)")))))
  (let [result (core/analyze-files
                [["a.clj" "(ns a (:require [b.c :as d])) (defn f [x] (+ x ::d/k))"]])]
    (is (empty? (:errors result)))
    (is (= 1 (count (:files result))))))

(deftest a-file-without-functions-does-not-break-the-summary
  (let [{:keys [summary files]} (core/analyze-files [["a.clj" "(ns a) (def x 1)"]])]
    (is (= 1 (:files summary)))
    (is (nil? (:maintainability-index (first files))))
    (is (nil? (:avg-maintainability summary)))))

(deftest the-average-covers-only-the-defined-indexes
  (let [src "(ns b) (defn f [x y] (if (> x y) (+ x 1) (* y 2)))"
        result (core/analyze-files [["a.clj" "(ns a) (def x 1)"] ["b.clj" src]])
        b-mi (->> (:files result) (filter #(= "b.clj" (:path %))) first
                  :maintainability-index-without-comments)]
    (is (number? b-mi))
    (is (= b-mi (:avg-maintainability (:summary result))))))

(deftest a-broken-file-is-reported-and-the-rest-still-analyzed
  (let [result (core/analyze-files [["a.clj" "(ns a) (defn f [x] (+ x 1))"]
                                    ["b.clj" "(defn "]])]
    (is (= ["a.clj"] (map :path (:files result))))
    (is (= ["b.clj"] (map :path (:errors result))))
    (is (string? (:error (first (:errors result)))))
    (is (= 1 (:unreadable (:summary result))))))

(deftest the-report-lists-what-it-could-not-read
  (let [md (report/render (core/analyze-files [["a.clj" "(ns a) (def x 1)"]
                                               ["b.clj" "(defn "]]))]
    (is (re-find #"Files not analyzed" md))
    (is (re-find #"b\.clj" md))))

(defn- near? [a b] (< (Math/abs (double (- a b))) 0.000001))

(defn- identical-fns [n]
  (apply str (for [i (range n)]
               (str "(defn f" i " [a b]\n  (if (> a b)\n    (+ a 1)\n    (* b 2)))\n"))))

(deftest file-index-does-not-measure-file-size
  (let [mi (fn [n] (:maintainability-index-without-comments
                    (core/analyze-source "x.clj" (identical-fns n))))]
    (is (pos? (mi 1)))
    (is (near? (mi 1) (mi 10)))
    (is (near? (mi 1) (mi 200)))))

(deftest file-index-averages-its-functions
  (let [file (core/analyze-source "x.clj" (identical-fns 3))
        f (first (:functions file))]
    (is (near? (:maintainability-index-without-comments f)
               (:maintainability-index-without-comments file)))))

(deftest a-function-index-ignores-comments-elsewhere-in-the-file
  (let [f "(defn f [a b]\n  (if (> a b) a b))"
        alone (first (:functions (core/analyze-source "x.clj" f)))
        noisy (first (:functions (core/analyze-source
                                  "x.clj" (str (apply str (repeat 100 ";; c\n")) f))))]
    (is (near? (:maintainability-index alone) (:maintainability-index noisy)))))

(deftest a-function-comment-weight-uses-its-own-comments
  (let [f (first (:functions (core/analyze-source
                              "x.clj" "(defn f\n  \"doc\"\n  [a b]\n  (+ a b))")))]
    (is (pos? (:comment-weight f)))))

(deftest a-function-knows-its-extent
  (let [[f g] (:functions (core/analyze-source
                           "x.clj" "\n(defn f [a]\n  ;; why\n  (inc a))\n\n(defn g [] 1)"))]
    (is (= [2 4] [(:line f) (:end-line f)]))
    (is (= {:loc 3 :sloc 2 :cloc 1 :blank 0} (:locs f)))
    (is (= [6 6] [(:line g) (:end-line g)]))))

(deftest methods-know-their-extent-too
  (let [f (first (:functions (core/analyze-source "x.clj" "(defrecord R [a]\n  P\n  (m [_]\n    (inc a)))")))]
    (is (= [3 4] [(:line f) (:end-line f)]))))

(deftest the-index-uses-real-lines-not-form-counts
  (let [f (first (:functions (core/analyze-source "x.clj" "(defn f [a] (g (h (i (j (k a))))))")))]
    (is (= 5 (:lloc f)))
    (is (= 1 (get-in f [:locs :sloc])))))

(deftest the-average-complexity-is-per-function
  (let [s (:summary (core/analyze-files
                     [["a.clj" "(defn a [] 1) (defn b [] 1) (defn c [] 1) (defn d [x] (if x 1 2))"]]))]
    (is (= 5/4 (:avg-cyclomatic s)))))

(deftest an-empty-project-has-no-averages
  (let [s (:summary (core/analyze-files []))]
    (is (nil? (:avg-cyclomatic s)))
    (is (nil? (:avg-maintainability s)))))

(deftest an-empty-project-renders
  (is (string? (report/render (core/analyze-files [])))))
