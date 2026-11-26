(ns metrics.references-test
  "References from outside the analyzed source: clj-kondo hooks, bb.edn and
  deps.edn task bodies, and files the config excludes."
  (:require [clojure.test :refer [deftest is testing]]
            [metrics.core :as core]
            [metrics.references :as references]
            [jolt.fs :as fs]))

(defn- temp-project
  "A directory holding these [relative-path source] files."
  [files]
  (let [dir (str (fs/create-temp-dir))]
    (doseq [[p src] files]
      (fs/create-dirs (fs/parent (str dir "/" p)))
      (spit (str dir "/" p) src))
    dir))

(defn- unused-public [dir & [opts]]
  (set (map :name (get-in (core/analyze-dir dir (merge {:abstractions false} opts))
                          [:cleanup :unused-public]))))

(def ^:private hook-src
  "(ns hooks.lib) (defn expand [m] m) (defn other [m] m) (defn dead [] 1)")

(deftest clj-kondo-hooks-are-references
  (testing "an exported config names its hooks"
    (let [dir (temp-project
               [["clj-kondo.exports/me/lib/hooks/lib.clj" hook-src]
                ["clj-kondo.exports/me/lib/config.edn"
                 "{:hooks {:analyze-call {lib/m hooks.lib/expand} :macroexpand {lib/n hooks.lib/other}}}"]])]
      (is (= #{"dead"} (unused-public dir)))))
  (testing "a project's own .clj-kondo config"
    (let [dir (temp-project
               [["resources/hooks/lib.clj" hook-src]
                [".clj-kondo/config.edn" "{:hooks {:analyze-call {lib/m hooks.lib/expand}}}"]
                ["src/lib.clj" "(ns lib) (defmacro m [x] x) (defn go [] (m 1))"]])]
      (is (= #{"dead" "other" "go"} (unused-public dir)))))
  (testing "a config with no hooks, or that won't read, adds nothing"
    (is (empty? (references/kondo-hooks {:lint-as {'a/b 'clojure.core/let}})))
    (is (empty? (references/config-references "x/.clj-kondo/config.edn" "{:hooks")))))

(deftest task-bodies-are-code
  (let [dir (temp-project
             [["src/tools.clj" "(ns my.tools) (defn build [] 1) (defn release [] 2) (defn dead [] 3)"]
              ["src/babashka/tasks.clj" "(ns babashka.tasks) (defn current-task [] {}) (defn clojure [& _] 0) (defn unused-task-fn [] 0)"]
              ["src/helper.clj" "(ns helper) (defn help [] 1) (defn idle [] 2)"]
              ["bb.edn"
               (str "{:tasks {:requires ([my.tools :as t])\n"
                    "         :enter (println (:name (current-task)))\n"
                    "         build {:task (t/build)}\n"
                    "         rel {:requires ([helper :refer [help]]) :task (do (help) (clojure \"-e\" \"1\"))}\n"
                    "         shout \"echo hi\"\n"
                    "         quoted {:task (println 'x @(future 1) `[1 ~(my.tools/release)] #(inc %))}}}")]])]
    (is (= #{"dead" "idle" "unused-task-fn"} (unused-public dir))))
  (testing "deps.edn's :tasks too, and a file without :tasks adds nothing"
    (let [dir (temp-project
               [["src/tools.clj" "(ns my.tools) (defn build [] 1) (defn dead [] 3)"]
                ["deps.edn" "{:paths [\"src\"] :tasks {b (my.tools/build)}}"]
                ["sub/bb.edn" "{:paths [\"x\"]}"]])]
      (is (= #{"dead"} (unused-public dir)))))
  (testing "task symbols resolve through the implicit babashka.tasks refer and :requires"
    (is (= #{"babashka.tasks/current-task" "my.tools/build" "helper/help" "a.b/c"}
           (references/task-references
            '{:requires ([my.tools :as t])
              :init (current-task)
              x {:requires ([helper :refer [help]]) :task (do (t/build) (help) (a.b/c))}})))))

(deftest excluded-files-are-read-for-references
  (let [files [["src/io.clj" "(ns sci.impl.io) (defn print-simple [x] x) (defn with-out [] 1) (defn dead [] 2)"]
               ["src/main.clj" "(ns app.main) (defn -main [] 1)"]
               ["vendor/sci/ns.cljc" "(ns sci.impl.namespaces (:require [sci.impl.io :as io])) (def m {'p sci.impl.io/print-simple 'w (io/with-out)}) (defn vendor-dead [] 1)"]
               ["vendor/other.ss" "(define (f) (dead))"]
               [".jolt-metrics.edn" "{:exclude [\"vendor/**\"]}"]]
        dir (temp-project files)
        result (core/analyze-dir dir {:exclude ["vendor/**"] :abstractions false})]
    (testing "a qualified or aliased reference from an excluded file is a use"
      (is (= #{"dead"} (set (map :name (get-in result [:cleanup :unused-public]))))
          "a Scheme mention of dead in an excluded file doesn't count by name"))
    (testing "excluded files stay out of every section"
      (is (= #{"src/io.clj" "src/main.clj"}
             (set (map #(subs (:path %) (inc (count dir))) (:files result)))))
      (is (not-any? #(re-find #"vendor" (str %))
                    (concat (:edges result) (:lints result) (:queue result)
                            (mapcat val (:cleanup result))))))
    (testing "an excluded file's calls don't count in the call graph"
      (let [f (first (filter #(= "print-simple" (:name %)) (mapcat :functions (:files result))))]
        (is (= 0 (:fan-in f)))))
    (testing "what git ignores is not read at all"
      (is (= #{"dead" "print-simple" "with-out"}
             (unused-public dir {:exclude ["vendor/**"] :ignored ["vendor/"]}))))))
