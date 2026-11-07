(ns metrics.git
  "Churn: how often each file changed recently, read from git history."
  (:require [metrics.paths :as paths]
            [clojure.java.shell :as shell]
            [clojure.string :as str]))

(defn parse-log
  "Count the paths of a `git log --name-only` listing, relative to dir."
  [dir text]
  (frequencies (map #(paths/prefixed dir %) (remove str/blank? (str/split-lines text)))))

(def ^:private max-since-days
  "git finds nothing for a --since before the epoch, so a window this long just
  means all of history."
  15000)

(defn changes
  "How many commits touched each file under dir in the last days days, or nil when
  there is no history to read."
  [dir days]
  (try
    (let [since (when (<= days max-since-days) [(str "--since=" days ".days")])
          args (concat ["git" "-C" dir "log"] since
                       ["--no-merges" "--name-only" "--format=" "--relative"])
          {:keys [exit out]} (apply shell/sh args)]
      (when (zero? exit)
        (parse-log dir out)))
    (catch Throwable _ nil)))
