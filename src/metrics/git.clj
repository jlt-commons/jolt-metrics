(ns metrics.git
  "What git history says: churn, how often each file changed recently, and how
  much each commit changed the code's entropy."
  (:require [metrics.entropy :as entropy]
            [metrics.paths :as paths]
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

(defn parse-ignored
  "The paths of a NUL-separated `git ls-files` listing."
  [text]
  (vec (remove str/blank? (str/split text #"\u0000"))))

(defn ignored
  "What git ignores under dir, relative to it: files, and directories with a
  trailing slash standing for all they hold.  Tracked files are never in it,
  whatever the patterns say.  nil outside a repository or without git."
  [dir]
  (try
    (let [{:keys [exit out]} (shell/sh "git" "-c" "core.quotePath=false" "-C" dir
                                       "ls-files" "-z" "--others" "--ignored"
                                       "--exclude-standard" "--directory")]
      (when (zero? exit)
        (parse-ignored out)))
    (catch Throwable _ nil)))

;; --- entropy history ---------------------------------------------------------

(def ^:private null-oid #"0+")

(defn- blob-id [oid]
  (when-not (re-matches null-oid oid) oid))

(defn- parse-change
  "A `git log --raw` line as {:path :old :new}, the blobs before and after, nil
  for a side where the file didn't exist."
  [dir line]
  (when-let [[_ old new path] (re-matches #":\d+ \d+ ([0-9a-f]+) ([0-9a-f]+) \S+\t(.+)" line)]
    {:path (paths/prefixed dir path) :old (blob-id old) :new (blob-id new)}))

(defn- parse-commit [dir chunk]
  (let [[header & lines] (str/split-lines chunk)
        [sha date subject] (str/split header #"\t" 3)]
    {:sha sha :date date :subject (or subject "")
     :changes (vec (keep #(parse-change dir %) lines))}))

(defn parse-raw-log
  "The commits of a `git log --raw` listing whose format starts each one with a
  NUL and then sha, date and subject between tabs, paths relative to dir."
  [dir text]
  (mapv #(parse-commit dir %) (remove str/blank? (str/split text #"\u0000"))))

(defn parse-batch
  "The blobs `git cat-file --batch` printed, as a map of id to content.  The
  text must be one char a byte, as ISO-8859-1 gives, for the sizes to line up.
  Missing objects are left out."
  [text]
  (loop [at 0
         blobs {}]
    (let [eol (str/index-of text "\n" at)]
      (if (nil? eol)
        blobs
        (let [[oid kind size] (str/split (subs text at eol) #" ")]
          (if (and size (not= kind "missing"))
            (let [start (inc eol)
                  end (+ start (parse-long size))]
              (recur (inc end) (assoc blobs oid (subs text start end))))
            (recur (inc eol) blobs)))))))

(def ^:private batch-size
  "How many blobs to ask git for at once, so a long history never sits in
  memory whole."
  200)

(defn- blob-entropies
  "The entropy of each blob, by id."
  [dir oids]
  (reduce (fn [acc batch]
            ;; bytes decoded here, not with :out-enc, which Jolt reads as UTF-8
            ;; whatever it names
            (let [{:keys [exit out]} (shell/sh "git" "-C" dir "cat-file" "--batch"
                                               :in (str (str/join "\n" batch) "\n")
                                               :out-enc :bytes)]
              (when-not (zero? exit)
                (throw (ex-info "git cat-file failed" {:dir dir})))
              (into acc (map (fn [[oid text]] [oid (entropy/source-entropy text)]))
                    (parse-batch (String. ^bytes out "ISO-8859-1")))))
          {}
          (partition-all batch-size oids)))

(defn commit-deltas
  "Each commit's change in entropy over the files keep? accepts, given blob
  entropies by id; commits that changed none of them are left out."
  [commits entropies keep?]
  (vec (for [{:keys [changes] :as c} commits
             :let [kept (filter #(keep? (:path %)) changes)]
             :when (seq kept)]
         (assoc (select-keys c [:sha :date :subject])
                :delta (reduce + 0.0 (for [{:keys [old new]} kept]
                                       (- (get entropies new 0.0) (get entropies old 0.0))))
                :files (mapv :path kept)))))

(defn history
  "The entropy change of each of the last n commits on the first-parent line
  under dir, oldest first, over the files keep? accepts (paths as the analyzer
  writes them); nil when there is no history to read.  A merge counts as one
  change against its first parent, so a merged branch is judged whole."
  [dir n keep?]
  (try
    (let [{:keys [exit out]} (shell/sh "git" "-c" "core.quotePath=false" "-C" dir "log"
                                       "--first-parent" "--diff-merges=first-parent"
                                       "--raw" "--no-abbrev" "--no-renames" "--relative"
                                       (str "--max-count=" n) "--reverse"
                                       "--format=%x00%H%x09%cs%x09%s")]
      (when (zero? exit)
        (let [commits (parse-raw-log dir out)
              kept (for [c commits, ch (:changes c) :when (keep? (:path ch))] ch)
              oids (distinct (keep identity (mapcat (juxt :old :new) kept)))]
          (commit-deltas commits (blob-entropies dir oids) keep?))))
    (catch Throwable _ nil)))
