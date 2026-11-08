(ns metrics.unusual-test
  "Unusual changes in the result and every report format."
  (:require [clojure.data.json :as json-text]
            [clojure.string :as str]
            [clojure.test :refer [deftest is]]
            [metrics.cli :as cli]
            [metrics.core :as core]
            [metrics.html :as html]
            [metrics.json :as json]
            [metrics.report :as report]))

(defn- commit [i delta]
  {:sha (str "abcdef1234567890" i) :date "2025-01-01" :subject (str "change " i " a|b")
   :delta (double delta) :files ["src/a.clj"]})

(def ^:private history
  (conj (mapv commit (range 10) (cycle [1.0 3.0])) (commit 10 6.0)))

(defn- analyzed [h]
  (core/analyze-files [["src/a.clj" "(ns a)"]] {:history h}))

(deftest the-result-has-the-unusual-changes-of-its-history
  (let [{:keys [commits changes]} (:unusual-changes (analyzed history))]
    (is (= 11 commits))
    (is (= [(assoc (commit 10 6.0) :z 4.0)] changes))))

(deftest no-history-means-no-unusual-changes
  (is (nil? (:unusual-changes (analyzed nil)))))

(deftest code-paths-are-measured-non-test-files
  (let [opts {:exclude ["src/gen/**"] :test-paths ["test"]}]
    (is (core/code-path? "." opts "src/a.clj"))
    (is (core/code-path? "." opts "src/a.scm"))
    (is (not (core/code-path? "." opts "README.md")))
    (is (not (core/code-path? "." opts "src/gen/b.clj")))
    (is (not (core/code-path? "." opts "test/a_test.clj")))
    (is (not (core/code-path? "." {} "tests/a_test.clj")))
    (is (core/code-path? "proj" {:exclude ["vendor/**"]} "proj/src/a.clj"))
    (is (not (core/code-path? "proj" {:exclude ["vendor/**"]} "proj/vendor/a.clj")))))

(deftest markdown-lists-them
  (let [md (report/render (analyzed history))]
    (is (str/includes? md "## Unusual changes"))
    (is (str/includes? md "the last 11 commits"))
    (is (str/includes? md "| abcdef12 | 2025-01-01 | +6.0 | 4.0 | 1 | change 10 a\\|b |"))))

(deftest markdown-says-when-nothing-was-unusual
  (let [md (report/render (analyzed (vec (take 10 history))))]
    (is (str/includes? md "## Unusual changes"))
    (is (str/includes? md "_none_"))))

(deftest markdown-leaves-the-section-out-without-history
  (is (not (str/includes? (report/render (analyzed nil)) "## Unusual changes"))))

(deftest json-has-them
  (let [u (get (json/report (analyzed history)) "unusual_changes")]
    (is (= 11 (get u "commits")))
    (is (= [{"commit" "abcdef123456789010" "date" "2025-01-01" "subject" "change 10 a|b"
             "delta" 6.0 "z" 4.0 "files" ["src/a.clj"]}]
           (get u "changes")))))

(deftest json-has-null-without-history
  (let [r (json/report (analyzed nil))]
    (is (contains? r "unusual_changes"))
    (is (nil? (get r "unusual_changes")))))

(deftest html-has-the-section
  (let [p (html/render (analyzed history))]
    (is (str/includes? p "<section id=\"unusual\""))
    (is (str/includes? p "change 10 a|b")))
  (is (not (str/includes? (html/render (analyzed nil)) "<section id=\"unusual\""))))

(deftest the-cli-reads-this-repository
  (let [data (read-string (:out (cli/run ["src" "--format" "edn" "--commits" "50"])))]
    (is (pos? (get-in data [:unusual-changes :commits])))
    (is (vector? (get-in data [:unusual-changes :changes])))))

(deftest the-cli-skips-it-without-git
  (let [data (json-text/read-str (:out (cli/run ["src" "--format" "json" "--no-git"])))]
    (is (nil? (get data "unusual_changes")))))

(deftest commits-takes-a-number
  (is (= 2 (:exit (cli/run ["src" "--commits" "lots"])))))

;; --- unusual files ---------------------------------------------------------

(defn- typical-file [i]
  [(str "src/f" i ".clj")
   (str "(ns f" i ")\n(defn get-user [db id] (fetch-user db id))\n;; "
        (str/join " " (take (+ 60 i) (cycle ["user" "account" "fetch" "store" "db"])))
        "\n")])

(def ^:private foreign-file
  ["src/vendor.clj"
   (str "(ns vendor)\n;; " (str/join " " (take 80 (cycle ["zebra" "quantum" "lattice" "xylophone"]))))])

(def ^:private project
  (conj (mapv typical-file (range 20)) foreign-file ["test/f_test.clj" "(ns f-test)"]))

(defn- by-path [result]
  (into {} (map (juxt :path identity)) (:files result)))

(deftest files-have-entropy-and-surprisal
  (let [files (by-path (core/analyze-files project {:root "."}))]
    (is (double? (:entropy (files "src/f1.clj"))))
    (is (double? (:surprisal (files "src/f1.clj"))))
    (is (nil? (:surprisal (files "test/f_test.clj"))))
    (is (not-any? :word-counts (vals files)))))

(deftest a-file-in-a-foreign-vocabulary-is-unusual
  (let [[u & more] (:unusual-files (core/analyze-files project {:root "."}))]
    (is (nil? more))
    (is (= "src/vendor.clj" (:path u)))
    (is (> (:z u) 3))
    (is (= 82 (:words u)))))

(deftest the-summary-has-the-entropy-spread
  (let [{:keys [files summary]} (core/analyze-files project {:root "."})
        code (remove :test? files)]
    (is (= (/ (reduce + (map :entropy code)) (count code)) (:avg-entropy summary)))))

(deftest markdown-lists-unusual-files
  (let [md (report/render (core/analyze-files project {:root "."}))]
    (is (str/includes? md "## Unusual files"))
    (is (re-find #"\| src/vendor\.clj \| [\d.]+ \| [\d.]+ \| 82 \|" md))
    (is (str/includes? md "| Average entropy per file |")))
  (is (str/includes? (report/render (core/analyze-files [["a.clj" "(ns a)"]])) "## Unusual files\n\n")))

(deftest json-has-unusual-files
  (let [r (json/report (core/analyze-files project {:root "."}))
        row (first (filter #(= "src/f1.clj" (get % "path")) (get r "files")))]
    (is (= ["src/vendor.clj"] (map #(get % "path") (get r "unusual_files"))))
    (is (= #{"path" "surprisal" "z" "words"} (set (keys (first (get r "unusual_files"))))))
    (is (number? (get row "entropy")))
    (is (number? (get row "surprisal")))
    (is (number? (get-in r ["summary" "entropy_avg"]))))
  (is (= [] (get (json/report (core/analyze-files [])) "unusual_files"))))

(deftest html-has-unusual-files
  (let [p (html/render (core/analyze-files project {:root "."}))]
    (is (str/includes? p "<section id=\"unusual-files\""))
    (is (str/includes? p "src/vendor.clj"))))
