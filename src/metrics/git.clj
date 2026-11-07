(ns metrics.git
  "Churn: how often each file changed recently, read from git history."
  (:require [clojure.java.shell :as shell]
            [clojure.string :as str]))

(defn- path-prefix
  "What to put before a path relative to dir so it matches the analyzer's paths."
  [dir]
  (let [d (-> dir
              (str/replace #"^\./" "")
              (str/replace #"/+$" ""))]
    (if (or (= "." d) (empty? d))
      ""
      (str d "/"))))

(defn parse-log
  "Count the paths of a `git log --name-only` listing, relative to dir."
  [dir text]
  (let [prefix (path-prefix dir)]
    (frequencies (map #(str prefix %) (remove str/blank? (str/split-lines text))))))

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
