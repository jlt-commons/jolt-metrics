(ns metrics.cli
  "Command-line entry point.

    jolt -m metrics.cli <dir> [out] [options]

  Exits 2 on bad usage or when <dir> does not exist.  A file that cannot be read
  does not fail the run: the report lists it under `Files not analyzed`."
  (:require [metrics.core :as core]
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
  -h, --help        Show this")

(defn- count-value
  "The non-negative number text means, or nil."
  [text]
  (when (and text (re-matches #"\d+" text))
    (parse-long text)))

(defn- parse-args
  "Parse args into options, with :error set if they make no sense."
  [args]
  (loop [args args
         opts {:format :md :days 90 :git true :top report/default-top :paths []}]
    (let [[a b] args]
      (cond
        (empty? args) opts
        (contains? #{"-h" "--help"} a) (assoc opts :help true)
        (= "--no-git" a) (recur (rest args) (assoc opts :git false))
        (= "--format" a) (if (contains? #{"md" "edn"} b)
                           (recur (drop 2 args) (assoc opts :format (keyword b)))
                           (assoc opts :error "--format takes md or edn"))
        (= "--days" a) (if-let [n (count-value b)]
                         (recur (drop 2 args) (assoc opts :days n))
                         (assoc opts :error "--days takes a number"))
        (= "--top" a) (if-let [n (count-value b)]
                        (recur (drop 2 args) (assoc opts :top n))
                        (assoc opts :error "--top takes a number"))
        (str/starts-with? a "-") (assoc opts :error (str "unknown option: " a))
        :else (recur (rest args) (update opts :paths conj a))))))

(defn- bad-usage [message]
  {:exit 2 :err (str message "\n\n" usage)})

(defn- render-result [result {:keys [format top]}]
  (if (= :edn format)
    (pr-str result)
    (report/render result {:top top})))

(defn run
  "Do the work of -main; returns {:exit n, :out text, :err text}."
  [args]
  (let [{:keys [error help paths days git] :as opts} (parse-args args)
        dir (or (first paths) ".")
        out (second paths)]
    (cond
      help {:exit 0 :out usage}
      error (bad-usage error)
      (> (count paths) 2) (bad-usage "at most a directory and an output file")
      (not (fs/directory? dir)) {:exit 2 :err (str "not a directory: " dir)}
      :else
      (let [changes (when git (git/changes dir days))
            text (render-result (core/analyze-dir dir {:changes changes :days days}) opts)]
        (if out
          (do (spit out text)
              {:exit 0 :out (str "wrote " out)})
          {:exit 0 :out text})))))

(defn -main [& args]
  (let [{:keys [exit out err]} (run args)]
    (when out (println out))
    (when err (binding [*out* *err*] (println err)))
    (flush)
    (System/exit exit)))
