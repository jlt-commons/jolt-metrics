(ns metrics.lint-report-test
  "The lint findings through the pipeline: in the result, the summary, and every
  report format."
  (:require [clojure.test :refer [deftest is testing]]
            [clojure.string :as str]
            [metrics.core :as core]
            [metrics.report :as report]
            [metrics.json :as json]
            [metrics.html :as html]))

(def ^:private source
  "(ns a.core)\n\n(defn f\n  \"Do it.\"\n  [x]\n  (do (g x)))\n")

(defn- analyze
  ([files] (analyze files nil))
  ([files opts] (core/analyze-files files (merge {:abstractions false} opts))))

(deftest lints-are-in-the-result
  (let [{:keys [lints summary files]} (analyze [["src/a/core.clj" source]])]
    (is (= [{:path "src/a/core.clj" :line 6 :column 3 :rule :redundant-do :level :warning
             :message "redundant do"}]
           lints))
    (is (= 1 (:lints summary)))
    (testing "the per-file copy is not in the public files"
      (is (not (contains? (first files) :lints))))))

(deftest a-project-sets-the-levels
  (is (empty? (:lints (analyze [["src/a/core.clj" source]]
                               {:linters {:redundant-do {:level :off}}}))))
  (is (= [:error] (map :level (:lints (analyze [["src/a/core.clj" source]]
                                               {:linters {:redundant-do {:level :error}}}))))))

(deftest scheme-is-not-linted
  (is (empty? (:lints (analyze [["src/a.scm" "(define (f x) (if x 1))"]])))))

(deftest every-format-shows-them
  (let [result (analyze [["src/a/core.clj" source]])]
    (is (str/includes? (report/render result) "## Lint findings"))
    (is (str/includes? (report/render result) "redundant do"))
    (is (= [{"path" "src/a/core.clj" "line" 6 "column" 3 "rule" "redundant_do"
             "level" "warning" "message" "redundant do"}]
           (get (json/report result) "lints")))
    (is (= 1 (get-in (json/report result) ["summary" "lints"])))
    (is (str/includes? (html/render result) "Lint findings"))))
