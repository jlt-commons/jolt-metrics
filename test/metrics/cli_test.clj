(ns metrics.cli-test
  "The command line: exit codes and where the output goes."
  (:require [clojure.test :refer [deftest is]]
            [clojure.string :as str]
            [metrics.cli :as cli]))

(deftest a-missing-directory-is-an-error
  (let [{:keys [exit err]} (cli/run ["/nonexistent/jolt-metrics"])]
    (is (= 2 exit))
    (is (str/includes? err "/nonexistent/jolt-metrics"))))

(deftest a-directory-prints-the-report
  (let [{:keys [exit out]} (cli/run ["src"])]
    (is (= 0 exit))
    (is (str/starts-with? out "# Code Metrics Report"))))
