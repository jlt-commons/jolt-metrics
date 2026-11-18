(ns metrics.abstractions-report-test
  "Abstraction opportunities in the result and every report format: found
  across files, tests aside, by the project's limits, and skippable."
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is]]
            [metrics.cli :as cli]
            [metrics.core :as core]
            [metrics.html :as html]
            [metrics.json :as json]
            [metrics.report :as report]
            [metrics.thresholds :as t]))

(defn- copy [n]
  (str "(defn " n " [m k]\n"
       "  (assoc m :status (compute-status (k m) :ready) :updated (now) :by \"system\"))\n"))

(def ^:private sources
  [["src/a.clj" (str "(ns a)\n" (copy "a1") (copy "a2"))]
   ["src/b.clj" (str "(ns b)\n" (copy "b1"))]
   ["test/c_test.clj" (str "(ns c-test)\n" (copy "t1") (copy "t2"))]])

(defn- analyzed
  ([] (analyzed nil))
  ([opts] (core/analyze-files sources (merge {:root "."} opts))))

(deftest the-result-has-the-shared-shape
  (let [[top :as found] (:abstractions (analyzed))]
    (is (= 1 (count found)))
    (is (= '(assoc ?a :status (compute-status (?b ?a) :ready) :updated (now) :by "system")
           (:pattern top)))
    (is (= 3 (:uses top)) "the copies in tests are left out")
    (is (= #{"a1" "a2" "b1"} (set (map :name (:sites top)))))
    (is (= #{"src/a.clj" "src/b.clj"} (set (map :path (:sites top)))))))

(deftest the-summary-counts-them
  (is (= 1 (:abstractions (:summary (analyzed))))))

(deftest they-go-by-the-projects-limits
  (is (= [] (:abstractions (analyzed {:thresholds {:abstractions {:min-size 40}}}))))
  (is (= [] (:abstractions (analyzed {:thresholds {:abstractions {:max-arity 1}}})))))

(deftest they-can-be-skipped
  (let [r (analyzed {:abstractions false})]
    (is (nil? (:abstractions r)))
    (is (nil? (:abstractions (:summary r))))))

(deftest the-thresholds-check-their-values
  (is (= {:min-size 8 :max-arity 4} (:abstractions t/defaults)))
  (is (nil? (t/problem {:abstractions {:min-size 12 :max-arity 3}})))
  (is (= ":abstractions :min-size should be a whole number"
         (t/problem {:abstractions {:min-size 2.5}}))))

(deftest markdown-has-a-section
  (let [md (report/render (analyzed))]
    (is (str/includes? md "## Abstraction opportunities"))
    (is (str/includes? md "`(assoc ?a :status"))
    (is (str/includes? md "patterns of at least 8 nodes"))
    (is (str/includes? md "| Abstraction opportunities | 1 |"))))

(deftest markdown-says-when-they-were-skipped
  (is (str/includes? (report/render (analyzed {:abstractions false}))
                     "Not looked for (`--no-abstractions`).")))

(deftest json-lists-them
  (let [r (json/report (analyzed))
        [a] (get r "abstractions")]
    (is (= 1 (get-in r ["summary" "abstractions"])))
    (is (str/starts-with? (get a "pattern") "(assoc ?a :status"))
    (is (= #{"pattern" "size" "arity" "uses" "saving" "sites"} (set (keys a))))
    (is (= #{"path" "name" "line"} (set (keys (first (get a "sites"))))))
    (is (= {"min_size" 8 "max_arity" 4} (get-in r ["thresholds" "abstractions"])))))

(deftest json-skipped-is-null
  (let [r (json/report (analyzed {:abstractions false}))]
    (is (contains? r "abstractions"))
    (is (nil? (get r "abstractions")))))

(deftest html-has-a-section
  (let [page (html/render (analyzed))]
    (is (str/includes? page "<section id=\"abstractions\""))
    (is (str/includes? page "(assoc ?a :status"))
    (is (str/includes? page "&quot;system&quot;") "pattern text is escaped")))

(deftest the-flag-turns-them-off
  (let [on (read-string (:out (cli/run ["src" "--format" "edn" "--no-git"])))
        off (read-string (:out (cli/run ["src" "--format" "edn" "--no-git" "--no-abstractions"])))]
    (is (vector? (:abstractions on)))
    (is (nil? (:abstractions off)))))
