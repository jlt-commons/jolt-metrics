(ns metrics.git-test
  "Reading churn out of git history."
  (:require [clojure.string]
            [clojure.test :refer [deftest is]]
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

(deftest parse-ignored-splits-on-nul
  (is (= ["target/" "src/a b.clj"] (git/parse-ignored "target/\u0000src/a b.clj\u0000"))))

(deftest outside-a-repository-nothing-is-ignored
  (is (nil? (git/ignored "/"))))

(def ^:private raw-log
  (str "\u0000aaa\t2025-01-02\tAdd b\n\n"
       ":000000 100644 0000000000000000000000000000000000000000 b1 A\tb.clj\n"
       ":100644 100644 a1 a2 M\ta.clj\n"
       "\u0000bbb\t2025-01-01\tFirst\tcommit\n\n"
       ":000000 100644 0000000000000000000000000000000000000000 a1 A\ta.clj\n"
       "\u0000ccc\t2025-01-03\tDocs only\n"))

(deftest parse-raw-log-reads-commits-and-their-blobs
  (is (= [{:sha "aaa" :date "2025-01-02" :subject "Add b"
           :changes [{:path "src/b.clj" :old nil :new "b1"}
                     {:path "src/a.clj" :old "a1" :new "a2"}]}
          {:sha "bbb" :date "2025-01-01" :subject "First\tcommit"
           :changes [{:path "src/a.clj" :old nil :new "a1"}]}
          {:sha "ccc" :date "2025-01-03" :subject "Docs only" :changes []}]
         (git/parse-raw-log "src" raw-log))))

(deftest parse-batch-splits-blobs-by-size
  (is (= {"b1" "ab\ncd" "a1" ""}
         (git/parse-batch "b1 blob 5\nab\ncd\na1 blob 0\n\nzz missing\n"))))

(deftest commit-deltas-sum-the-entropy-changes-of-kept-files
  (let [commits (git/parse-raw-log "." raw-log)
        entropy {"a1" 1.0 "a2" 3.5 "b1" 2.0}]
    (is (= [{:sha "aaa" :date "2025-01-02" :subject "Add b" :delta 4.5
             :files ["b.clj" "a.clj"]}
            {:sha "bbb" :date "2025-01-01" :subject "First\tcommit" :delta 1.0
             :files ["a.clj"]}]
           (git/commit-deltas commits entropy (constantly true))))
    (is (= [{:sha "aaa" :date "2025-01-02" :subject "Add b" :delta 2.5 :files ["a.clj"]}
            {:sha "bbb" :date "2025-01-01" :subject "First\tcommit" :delta 1.0 :files ["a.clj"]}]
           (git/commit-deltas commits entropy #(= "a.clj" %))))))

(deftest history-reads-this-repository-oldest-first
  (let [h (git/history "src" 20 #(clojure.string/ends-with? % ".clj"))]
    (is (seq h))
    (is (<= (count h) 20))
    (is (every? #(clojure.string/starts-with? % "src/") (mapcat :files h)))
    (is (every? double? (map :delta h)))
    (is (= (map :date h) (sort (map :date h))))))

(deftest outside-a-repository-there-is-no-commit-history
  (is (nil? (git/history "/" 20 (constantly true)))))
