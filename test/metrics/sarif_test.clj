(ns metrics.sarif-test
  (:require [clojure.test :refer [deftest is testing]]
            [clojure.data.json :as data-json]
            [metrics.core :as core]
            [metrics.sarif :as sarif]))

(def ^:private big
  (str "(ns a)\n(defn f\n  \"Do it.\"\n  [x]\n  (do (g x))\n"
       (apply str (repeat 30 "  (g x)\n")) "  x)\n(defn- unused [] 1)\n"))

(defn- result []
  (core/analyze-files [["src/a.clj" big]] {:abstractions false}))

(defn- results-of [log rule]
  (filter #(= rule (get % "ruleId")) (get-in log ["runs" 0 "results"])))

(deftest the-log-is-sarif
  (let [log (sarif/report (result))]
    (is (= "2.1.0" (get log "version")))
    (is (= "jolt-metrics" (get-in log ["runs" 0 "tool" "driver" "name"])))
    (testing "every rule a result names is described"
      (let [ids (set (map #(get % "id") (get-in log ["runs" 0 "tool" "driver" "rules"])))]
        (is (every? ids (map #(get % "ruleId") (get-in log ["runs" 0 "results"]))))))))

(deftest lints-are-results
  (let [[r] (results-of (sarif/report (result)) "redundant-do")]
    (is (= "warning" (get r "level")))
    (is (= "redundant do" (get-in r ["message" "text"])))
    (is (= {"artifactLocation" {"uri" "src/a.clj"}
            "region" {"startLine" 5 "startColumn" 3}}
           (get-in r ["locations" 0 "physicalLocation"])))))

(deftest queue-entries-are-results
  (let [[r] (results-of (sarif/report (result)) "lines")]
    (is (= "warning" (get r "level")))
    (is (re-find #"f: lines is \d+, limit \d+" (get-in r ["message" "text"])))
    (is (= 2 (get-in r ["locations" 0 "physicalLocation" "region" "startLine"])))))

(deftest cleanup-candidates-are-notes
  (let [[r] (results-of (sarif/report (result)) "unused-private")]
    (is (= "note" (get r "level")))
    (is (= 37 (get-in r ["locations" 0 "physicalLocation" "region" "startLine"])))))

(deftest it-renders-as-json
  (is (= (sarif/report (result)) (data-json/read-str (sarif/render (result))))))
