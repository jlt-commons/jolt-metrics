(ns metrics.language
  "The languages this analyzes.  The pipeline only ever sees an adapter:

    :id              a keyword naming the language
    :extensions      the file extensions that are this language
    :globs           what a directory scan looks for
    :parse           source text to {:forms :dependencies}, the forms being in the
                     shapes metrics.reader's walker understands
    :scan            source text to line classes and list extents; see metrics.scanner
    :unused-public?  can a public function nothing mentions be called unused?
    :index           optional: path and source text to a jolt-lsp analysis, which
                     puts the file's functions in the call graph; see
                     metrics.callgraph

  Adding a language means writing an adapter and listing it here."
  (:require [metrics.clojure :as clojure-language]
            [metrics.scheme :as scheme-language]
            [clojure.string :as str]))

(def ^:private languages
  [clojure-language/language scheme-language/language])

(defn- extension
  "The lower-cased text after the last dot of a path's file name, or nil."
  [path]
  (let [file-name (last (str/split path #"/"))
        dot (str/last-index-of file-name ".")]
    (when dot
      (str/lower-case (subs file-name (inc dot))))))

(defn of
  "The adapter for a path, or nil if it is no language we analyze."
  [path]
  (let [ext (extension path)]
    (first (filter #(contains? (:extensions %) ext) languages))))

(defn globs
  "Everything a directory scan should look for."
  []
  (mapcat :globs languages))
