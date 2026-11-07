(ns metrics.git-test
  "Reading churn out of git history."
  (:require [clojure.test :refer [deftest is]]
            [metrics.git :as git]))

(deftest parse-log-counts-per-file
  (is (= {"src/a.clj" 2 "src/b.clj" 1}
         (git/parse-log "src" "a.clj\nb.clj\n\na.clj\n"))))

(deftest parse-log-keeps-paths-in-the-analyzer-form
  (is (= {"a.clj" 1} (git/parse-log "." "a.clj\n")))
  (is (= {"src/a.clj" 1} (git/parse-log "./src/" "a.clj\n")))
  (is (= {"/x/y/a.clj" 1} (git/parse-log "/x/y" "a.clj\n"))))

(deftest changes-reads-this-repository
  (is (pos? (get (git/changes "src" 36500) "src/metrics/cli.clj" 0))))

(deftest outside-a-repository-there-is-no-history
  (is (nil? (git/changes "/" 90))))
