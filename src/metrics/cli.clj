(ns metrics.cli
  "Command-line entry point.

    jolt -m metrics.cli <dir> [out] [options]

  Exits 2 on bad usage or when <dir> does not exist.  A file that cannot be read
  does not fail the run: the report lists it under `Files not analyzed`."
  (:require [metrics.config :as config]
            [metrics.core :as core]
            [metrics.git :as git]
            [metrics.report :as report]
            [clojure.string :as str]
            [jolt.fs :as fs]))

(def usage
  "Usage: jolt -m metrics.cli <dir> [out] [options]

Prints the report, or writes it to out.

  --format md|edn   Markdown for reading, EDN with every finding (default md)
  --top N           Queue entries to show in Markdown (default 25)
  --days N          How much git history counts as recent churn (default 90)
  --no-git          Skip git history: no churn and no hotspots
  --exclude GLOB    Leave out matching files, relative to <dir>; can be repeated
  --config FILE     The config file (default <dir>/.jolt-metrics.edn)
  -h, --help        Show this")

(defn- count-value
  "The non-negative number text means, or nil."
  [text]
  (when (and text (re-matches #"\d+" text))
    (parse-long text)))

(def ^:private default-options
  {:format :md :days 90 :git true :top report/default-top :paths []})

(def ^:private value-options
  "Options that take a value: which key it sets, how to read it, and what to say
  when it can't be read."
  {"--format" {:key :format
               :parse #(when (contains? #{"md" "edn"} %) (keyword %))
               :error "--format takes md or edn"}
   "--days" {:key :days :parse count-value :error "--days takes a number"}
   "--top" {:key :top :parse count-value :error "--top takes a number"}
   "--exclude" {:key :exclude :parse identity :many true :error "--exclude takes a glob"}
   "--config" {:key :config :parse identity :error "--config takes a file"}})

(defn- with-option
  "opts with an option's value set, or added to the others if it can be repeated."
  [opts {:keys [key many]} value]
  (if many
    (update opts key (fnil conj []) value)
    (assoc opts key value)))

(defn- parse-args
  "Parse args into options, with :error set if they make no sense."
  [args]
  (loop [args args
         opts default-options]
    (let [[a b] args
          option (get value-options a)
          value (when option ((:parse option) b))]
      (cond
        (empty? args) opts
        (contains? #{"-h" "--help"} a) (assoc opts :help true)
        (= "--no-git" a) (recur (rest args) (assoc opts :git false))
        (some? value) (recur (drop 2 args) (with-option opts option value))
        option (assoc opts :error (:error option))
        (str/starts-with? a "-") (assoc opts :error (str "unknown option: " a))
        :else (recur (rest args) (update opts :paths conj a))))))

(defn- bad-usage [message]
  {:exit 2 :err (str message "\n\n" usage)})

(defn- render-result [result {:keys [format top]}]
  (if (= :edn format)
    (pr-str result)
    (report/render result {:top top})))

(defn- analyze
  "Analyze dir, with the flags adding to what the config says."
  [dir project-config {:keys [git days exclude]}]
  (core/analyze-dir dir {:changes (when git (git/changes dir days))
                         :days days
                         :exclude (concat (:exclude project-config) exclude)
                         :test-paths (:test-paths project-config)}))

(defn- report
  "Run the analysis and put the result where it was asked to go."
  [dir out project-config opts]
  (let [text (render-result (analyze dir project-config opts) opts)]
    (if out
      (do (spit out text)
          {:exit 0 :out (str "wrote " out)})
      {:exit 0 :out text})))

(defn run
  "Do the work of -main; returns {:exit n, :out text, :err text}."
  [args]
  (let [{:keys [error help paths config] :as opts} (parse-args args)
        dir (or (first paths) ".")
        project-config (when-not (or help error) (config/load-config dir config))]
    (cond
      help {:exit 0 :out usage}
      error (bad-usage error)
      (> (count paths) 2) (bad-usage "at most a directory and an output file")
      (not (fs/directory? dir)) {:exit 2 :err (str "not a directory: " dir)}
      (:error project-config) {:exit 2 :err (:error project-config)}
      :else (report dir (second paths) project-config opts))))

(defn -main [& args]
  (let [{:keys [exit out err]} (run args)]
    (when out (println out))
    (when err (binding [*out* *err*] (println err)))
    (flush)
    (System/exit exit)))
