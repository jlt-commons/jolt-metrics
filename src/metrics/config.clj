(ns metrics.config
  "The optional project config file, .jolt-metrics.edn in the analyzed directory:

    {:exclude [\"vendor/**\" \"**/generated/*\"]
     :test-paths [\"test\" \"bench\"]
     :api-namespaces [\"my.lib\" \"my.lib.api.*\"]
     :api-paths [\"src/my/lib/core\"]}

  Patterns are relative to that directory.  It is data, read as EDN, so it can't run
  anything."
  (:require [clojure.edn :as edn]
            [jolt.fs :as fs]))

(def file-name ".jolt-metrics.edn")

(def ^:private known-keys #{:exclude :test-paths :api-namespaces :api-paths})

(defn- problem
  "What is wrong with a parsed config, or nil."
  [config]
  (let [unknown (remove known-keys (when (map? config) (keys config)))
        lists? (fn [k] (let [v (get config k [])]
                         (and (sequential? v) (every? string? v))))]
    (cond
      (not (map? config)) "it should be a map"
      (seq unknown) (str "unknown keys: " (pr-str (vec unknown)))
      (not (every? lists? known-keys)) (str (pr-str (vec known-keys)) " take lists of strings")
      :else nil)))

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
