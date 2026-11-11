(ns metrics.json-test
  "The lean JSON report: its shape, and that it encodes whatever the result holds."
  (:require [clojure.data.json]
            [clojure.string]
            [clojure.test :refer [deftest is]]
            [metrics.core :as core]
            [metrics.json :as json]))

(deftest the-report-has-the-lean-shape
  (let [r (json/report (core/analyze-files [["a.clj" "(ns a) (defn f [x] (if x 1 2))"]]))]
    (is (= #{"tool" "schema_version" "summary" "findings" "hotspots" "unusual_changes" "unusual_files" "cleanup"
             "errors" "files" "dependencies"} (set (keys r))))
    (is (= "jolt-metrics" (get r "tool")))
    (is (= 1 (get r "schema_version")))
    (is (= ["a.clj"] (map #(get % "path") (get r "files"))))
    (is (= "a" (get-in r ["files" 0 "namespace"])))))

(deftest findings-keep-queue-order-and-snake-case
  (let [big (str "(ns a)\n(defn f [x]\n" (apply str (repeat 60 "  (g x)\n")) "  x)")
        r (json/report (core/analyze-files [["a.clj" big]]))
        f (first (get r "findings"))]
    (is (= 1 (get f "rank")))
    (is (= "function" (get f "kind")))
    (is (contains? f "end_line"))
    (is (some #(= "lines" (get % "rule")) (get f "violations")))
    (is (= #{"cyclomatic" "cognitive" "nesting" "lines" "params" "maintainability"
             "fan_in" "fan_out" "reach"}
           (set (keys (get f "metrics")))))))

(deftest no-history-means-null-hotspots
  (is (nil? (get (json/report (core/analyze-files [["a.clj" "(ns a)"]])) "hotspots"))))

(deftest an-empty-result-has-every-key
  (let [r (json/report (core/analyze-files []))]
    (is (= [] (get r "findings") (get r "files") (get r "errors") (get r "dependencies")))
    (is (= [] (get-in r ["cleanup" "unused_requires"])))))

(deftest non-finite-numbers-become-null
  (let [result (assoc-in (core/analyze-files [["a.clj" "(ns a)"]])
                         [:summary :avg-maintainability] ##NaN)]
    (is (nil? (get-in (json/report result) ["summary" "maintainability_avg"])))
    (is (string? (json/render result)))))

(deftest ratios-become-rounded-decimals
  (let [result (assoc-in (core/analyze-files [["a.clj" "(ns a)"]]) [:summary :avg-cyclomatic] 800/307)]
    (is (= 2.606 (get-in (json/report result) ["summary" "cyclomatic_avg"])))))

(deftest render-reads-back
  (let [text (json/render (core/analyze-files [["a.clj" "(ns a (:require [b :as unused]))"]]))
        back (clojure.data.json/read-str text)]
    (is (= {"path" "a.clj" "lib" "b" "alias" "unused" "refers" []}
           (first (get-in back ["cleanup" "unused_requires"]))))))

(deftest paths-keep-their-slashes
  (is (clojure.string/includes? (json/render (core/analyze-files [["src/a.clj" "(ns a)"]]))
                                "\"path\":\"src/a.clj\"")))
