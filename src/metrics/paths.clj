(ns metrics.paths
  "Paths relative to the analysis root, and the glob patterns that select them."
  (:require [clojure.string :as str]))

(defn- clean-dir
  "A directory with no leading ./ or trailing /; the current one is empty."
  [dir]
  (let [d (-> (or dir "")
              (str/replace #"^\./" "")
              (str/replace #"/+$" ""))]
    (if (= "." d) "" d)))

(defn relative
  "path relative to root, or as it is when it lies elsewhere."
  [root path]
  (let [d (clean-dir root)
        prefix (str d "/")]
    (if (str/starts-with? path prefix)
      (subs path (count prefix))
      path)))

(defn prefixed
  "A path relative to root, in the form the analyzer's paths take."
  [root path]
  (let [d (clean-dir root)]
    (if (empty? d) path (str d "/" path))))

(def ^:private glob-tokens
  "The glob syntax, with the longest first so that ** beats *."
  #"\*\*/|\*\*|\*|\?|[^*?]+")

(defn- quote-literal [text]
  (str/replace text #"[\\.+()\[\]{}^$|]" (fn [m] (str "\\" m))))

(defn- token->regex [token]
  (case token
    "**/" "(?:.*/)?"
    "**" ".*"
    "*" "[^/]*"
    "?" "[^/]"
    (quote-literal token)))

(defn- glob->regex
  "A pattern as a regex.  It also matches what lies under what it names, so a bare
  directory covers its contents."
  [glob]
  (let [pattern (str/replace glob #"/+$" "")]
    (re-pattern (str (apply str (map token->regex (re-seq glob-tokens pattern)))
                     "(?:/.*)?"))))

(defn glob-match?
  "Does a path relative to the root match a glob?"
  [glob path]
  (boolean (re-matches (glob->regex glob) path)))

(defn excluded?
  "Does any of the globs match a path relative to the root?"
  [globs path]
  (boolean (some #(glob-match? % path) globs)))

(defn- plain-name? [pattern]
  (not (re-find #"[*?/]" pattern)))

(defn test-file?
  "Is a path relative to the root under a test directory?  A plain pattern names a
  directory at any depth, and any other is a glob."
  [patterns path]
  (let [directories (set (butlast (str/split path #"/")))]
    (boolean (some #(if (plain-name? %)
                      (contains? directories %)
                      (glob-match? % path))
                   patterns))))
