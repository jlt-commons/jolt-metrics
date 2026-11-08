(ns metrics.cli-test
  "The command line: exit codes and where the output goes."
  (:require [clojure.data.json]
            [clojure.test :refer [deftest is]]
            [clojure.string :as str]
            [clojure.java.shell :as shell]
            [metrics.cli :as cli]
            [jolt.fs :as fs]))

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
  (is (= 2 (:exit (cli/run ["src" "--top"]))))
  (is (= 2 (:exit (cli/run ["src" "--format" "pdf"]))))
  (is (= 2 (:exit (cli/run ["src" "--format"])))))

(deftest help-is-not-an-error
  (let [{:keys [exit out]} (cli/run ["--help"])]
    (is (= 0 exit))
    (is (str/includes? out "--format"))))

(deftest json-output-reads-back
  (let [{:keys [exit out]} (cli/run ["src" "--format" "json" "--no-git"])
        data (clojure.data.json/read-str out)]
    (is (= 0 exit))
    (is (= 1 (get data "schema_version")))
    (is (nil? (get data "hotspots")))))

(deftest html-output-is-a-page
  (let [{:keys [exit out]} (cli/run ["src" "--format" "html" "--no-git"])]
    (is (= 0 exit))
    (is (str/starts-with? out "<!doctype html>"))))

(deftest the-output-extension-picks-the-format
  (let [dir (str (fs/create-temp-dir))]
    (doseq [[file prefix] [["r.html" "<!doctype html>"] ["r.HTM" "<!doctype html>"]
                           ["r.json" "{"] ["r.edn" "{:"] ["r.md" "# Code Metrics Report"]
                           ["r.txt" "# Code Metrics Report"]]]
      (is (zero? (:exit (cli/run ["src" (str dir "/" file) "--no-git"]))) file)
      (is (str/starts-with? (slurp (str dir "/" file)) prefix) file))))

(deftest an-explicit-format-beats-the-extension
  (let [path (str (fs/create-temp-dir) "/r.html")]
    (cli/run ["src" path "--format" "md" "--no-git"])
    (is (str/starts-with? (slurp path) "# Code Metrics Report"))))

;; --- exclusions and the config file ----------------------------------------

(defn- temp-project
  "A directory holding these [relative-path source] files."
  [files]
  (let [dir (str (fs/create-temp-dir))]
    (doseq [[p src] files]
      (fs/create-dirs (fs/parent (str dir "/" p)))
      (spit (str dir "/" p) src))
    dir))

(defn- analyzed-paths [dir & flags]
  (let [data (read-string (:out (cli/run (concat [dir "--format" "edn" "--no-git"] flags))))]
    (set (map #(subs (:path %) (inc (count dir))) (:files data)))))

(deftest exclude-drops-files
  (let [dir (temp-project [["src/a.clj" "(ns a)"] ["vendor/b.clj" "(ns b)"] ["gen/c.ss" "(define x 1)"]])]
    (is (= #{"src/a.clj" "vendor/b.clj" "gen/c.ss"} (analyzed-paths dir)))
    (is (= #{"src/a.clj" "gen/c.ss"} (analyzed-paths dir "--exclude" "vendor/**")))
    (is (= #{"src/a.clj"} (analyzed-paths dir "--exclude" "vendor/**" "--exclude" "gen")))))

(defn- git-project
  "A temp-project that is also a git repository with everything committed but
  what its .gitignore leaves out."
  [files]
  (let [dir (temp-project files)
        git #(apply shell/sh "git" "-C" dir %&)]
    (git "init" "-q")
    (git "add" ".")
    (git "-c" "user.name=t" "-c" "user.email=t@t" "commit" "-qm" "init")
    dir))

(deftest what-git-ignores-is-left-out
  (let [src "(ns demo.core) (defn f [xs] xs)"
        dir (git-project [[".gitignore" "target/\n"]
                          ["src/demo/core.clj" src]
                          ["target/classes/demo/core.clj" src]])]
    (spit (str dir "/src/new.clj") "(ns demo.new)")
    (is (= #{"src/demo/core.clj" "src/new.clj"} (analyzed-paths dir)))
    (is (= #{"src/demo/core.clj" "src/new.clj" "target/classes/demo/core.clj"}
           (analyzed-paths dir "--no-gitignore")))))

(deftest a-subdirectory-of-a-repository-uses-its-gitignore
  (let [dir (git-project [["src/.gitignore" "gen/\n"] ["src/a.clj" "(ns a)"] ["src/gen/b.clj" "(ns b)"]])
        sub (str dir "/src")
        data (read-string (:out (cli/run [sub "--format" "edn" "--no-git"])))]
    (is (= [(str sub "/a.clj")] (map :path (:files data))))))

(deftest a-tracked-file-counts-even-when-a-pattern-matches-it
  (let [dir (git-project [["a.clj" "(ns a)"] ["gen/b.clj" "(ns b)"]])]
    (spit (str dir "/.gitignore") "gen/\n")
    (is (= #{"a.clj" "gen/b.clj"} (analyzed-paths dir)))))

(deftest the-config-file-is-read-from-the-directory
  (let [dir (temp-project [["src/a.clj" "(ns a)"] ["vendor/b.clj" "(ns b)"]
                           [".jolt-metrics.edn" "{:exclude [\"vendor/**\"]}"]])]
    (is (= #{"src/a.clj"} (analyzed-paths dir)))))

(deftest the-command-line-adds-to-the-config
  (let [dir (temp-project [["a.clj" "(ns a)"] ["b.clj" "(ns b)"] ["c.clj" "(ns c)"]
                           [".jolt-metrics.edn" "{:exclude [\"a.clj\"]}"]])]
    (is (= #{"c.clj"} (analyzed-paths dir "--exclude" "b.clj")))))

(deftest a-config-can-name-test-directories
  (let [big (str "(defn f [x]\n" (apply str (repeat 60 "  (g x)\n")) "  x)")
        dir (temp-project [["checks/a.clj" big] [".jolt-metrics.edn" "{:test-paths [\"checks\"]}"]])
        data (read-string (:out (cli/run [dir "--format" "edn" "--no-git"])))]
    (is (empty? (:queue data)))))

(deftest a-config-can-name-api-namespaces
  (let [dir (temp-project [["src/lib.clj" "(ns my.lib) (defn api [] 1)"]
                           ["src/app.clj" "(ns my.app) (defn dead [] 1)"]
                           [".jolt-metrics.edn" "{:api-namespaces [\"my.lib\"]}"]])
        data (read-string (:out (cli/run [dir "--format" "edn" "--no-git"])))]
    (is (= ["dead"] (map :name (get-in data [:cleanup :unused-public]))))))

(deftest a-config-can-name-api-paths
  (let [dir (temp-project [["core/a.clj" "(defn api [] 1)"]
                           ["src/app.clj" "(ns my.app) (defn dead [] 1)"]
                           [".jolt-metrics.edn" "{:api-paths [\"core\"]}"]])
        data (read-string (:out (cli/run [dir "--format" "edn" "--no-git"])))]
    (is (= ["dead"] (map :name (get-in data [:cleanup :unused-public]))))))

(deftest a-config-can-be-named
  (let [dir (temp-project [["a.clj" "(ns a)"] ["b.clj" "(ns b)"]])
        config (str (fs/create-temp-dir) "/mine.edn")]
    (spit config "{:exclude [\"a.clj\"]}")
    (is (= #{"b.clj"} (analyzed-paths dir "--config" config)))))

(deftest a-bad-config-is-an-error
  (let [broken (temp-project [["a.clj" "(ns a)"] [".jolt-metrics.edn" "{:exclude ["]])
        wrong (temp-project [["a.clj" "(ns a)"] [".jolt-metrics.edn" "[1 2]"]])
        unknown (temp-project [["a.clj" "(ns a)"] [".jolt-metrics.edn" "{:exlude []}"]])
        not-a-list (temp-project [["a.clj" "(ns a)"] [".jolt-metrics.edn" "{:api-namespaces \"a.*\"}"]])]
    (is (= 2 (:exit (cli/run [broken]))))
    (is (= 2 (:exit (cli/run [wrong]))))
    (is (str/includes? (:err (cli/run [unknown])) ":exlude"))
    (is (= 2 (:exit (cli/run [not-a-list]))))
    (is (= 2 (:exit (cli/run ["src" "--config" "/nonexistent/x.edn"]))))))

(deftest a-config-cannot-run-code
  (let [dir (temp-project [["a.clj" "(ns a)"] [".jolt-metrics.edn" "{:exclude #=(+ 1 2)}"]])]
    (is (= 2 (:exit (cli/run [dir]))))))
