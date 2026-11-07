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

(deftest edn-output-reads-back
  (let [{:keys [exit out]} (cli/run ["src" "--format" "edn" "--no-git"])
        data (read-string out)]
    (is (= 0 exit))
    (is (seq (:files data)))
    (is (contains? data :queue))
    (is (contains? data :cleanup))))

(deftest git-history-feeds-the-report
  (let [data (read-string (:out (cli/run ["src" "--format" "edn" "--days" "36500"])))]
    (is (= 36500 (:days data)))
    (is (pos? (get-in data [:changes "src/metrics/cli.clj"] 0)))))

(deftest no-git-means-no-history
  (let [data (read-string (:out (cli/run ["src" "--format" "edn" "--no-git"])))]
    (is (nil? (:changes data)))))

(deftest top-caps-the-markdown-queue
  (is (zero? (:exit (cli/run ["src" "--top" "1"])))))

(deftest output-still-goes-to-a-file
  (let [path (str (System/getProperty "java.io.tmpdir") "/jolt-metrics-cli-test.md")
        {:keys [exit out]} (cli/run ["src" path "--no-git"])]
    (is (= 0 exit))
    (is (str/includes? out path))
    (is (str/starts-with? (slurp path) "# Code Metrics Report"))))

(deftest bad-usage-is-an-error
  (is (= 2 (:exit (cli/run ["src" "--format" "xml"]))))
  (is (= 2 (:exit (cli/run ["src" "--bogus"]))))
  (is (= 2 (:exit (cli/run ["src" "--days" "soon"]))))
  (is (= 2 (:exit (cli/run ["src" "--top"])))))

(deftest help-is-not-an-error
  (let [{:keys [exit out]} (cli/run ["--help"])]
    (is (= 0 exit))
    (is (str/includes? out "--format"))))
