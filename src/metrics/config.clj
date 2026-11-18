(ns metrics.config
  "The optional project config file, .jolt-metrics.edn in the analyzed directory:

    {:exclude [\"vendor/**\" \"**/generated/*\"]
     :test-paths [\"test\" \"bench\"]
     :api-namespaces [\"my.lib\" \"my.lib.api.*\"]
     :api-paths [\"src/my/lib/core\"]
     :thresholds {:cyclomatic {:warning 8 :critical 15}}}

  Patterns are relative to that directory, and metrics.thresholds says what
  :thresholds takes.  It is data, read as EDN, so it can't run anything."
  (:require [clojure.edn :as edn]
            [metrics.thresholds :as thresholds]
            [jolt.fs :as fs]))

(def file-name ".jolt-metrics.edn")

(def ^:private list-keys #{:exclude :test-paths :api-namespaces :api-paths})

(def ^:private known-keys (conj list-keys :thresholds))

(defn- problem
  "What is wrong with a parsed config, or nil."
  [config]
  (let [unknown (remove known-keys (when (map? config) (keys config)))
        lists? (fn [k] (let [v (get config k [])]
                         (and (sequential? v) (every? string? v))))]
    (cond
      (not (map? config)) "it should be a map"
      (seq unknown) (str "unknown keys: " (pr-str (vec unknown)))
      (not (every? lists? list-keys)) (str (pr-str (vec list-keys)) " take lists of strings")
      :else (thresholds/problem (:thresholds config)))))

(defn- parse
  "The config in a file, or {:error message}."
  [path]
  (try
    (let [config (edn/read-string (slurp path))
          issue (problem config)]
      (if issue
        {:error (str path ": " issue)}
        config))
    (catch Exception e
      {:error (str path ": " (or (ex-message e) e))})))

(defn load-config
  "The config for dir: the named file, which has to exist, or else the one in dir if
  there is one.  {:error message} if it can't be used."
  [dir named]
  (let [default (str dir "/" file-name)]
    (cond
      named (if (fs/exists? named)
              (parse named)
              {:error (str "no such config file: " named)})
      (fs/exists? default) (parse default)
      :else {})))
