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
        b-mi (->> (:files result) (filter #(= "b.clj" (:path %))) first :maintainability-index)]
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

(deftest an-empty-project-renders
  (is (string? (report/render (core/analyze-files [])))))
