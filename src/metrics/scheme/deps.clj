(ns metrics.scheme.deps
  "What a Scheme file depends on.  Reads the forms as written, before they are
  normalized, since a load is a call like any other once they have been.

  Files connect through load and include, which take paths, and R6RS libraries
  through import.  A library's name reads as a namespace: (foo bar) is foo.bar."
  (:require [clojure.string :as str]))

(def ^:private file-loaders #{"load" "include" "##include"})

(def ^:private import-wrappers
  "Import sets that say which names to take from the library inside them."
  '#{only except prefix rename for library})

(defn- call-of? [form heads]
  (and (seq? form) (symbol? (first form)) (contains? heads (name (first form)))))

(defn- code-forms
  "Every list in forms that could be code: quoted data holds no loads."
  [forms]
  (mapcat (fn [form]
            (when (and (seq? form)
                       (not (contains? '#{quote quasiquote} (first form))))
              (cons form (code-forms form))))
          forms))

(defn- load-paths
  "The literal paths forms load; one computed at runtime can't be known from here."
  [forms]
  (distinct
   (for [form (code-forms forms)
         :when (and (call-of? form file-loaders) (string? (second form)))]
     (second form))))

(defn- library-name
  "A library's name as a namespace, without its version, which is a list of numbers."
  [spec]
  (str/join "." (map str (remove seq? spec))))

(defn- imported-library
  "The library in an import set, or nil for a local module."
  [spec]
  (cond
    (not (seq? spec)) nil
    (contains? import-wrappers (first spec)) (imported-library (second spec))
    :else (library-name spec)))

(defn- import-clause-libraries [[_ & specs]]
  (keep imported-library specs))

(defn- library-form [forms]
  (first (filter #(and (seq? %) (= 'library (first %))) forms)))

(defn- import-forms
  "The top-level imports, and those of a library."
  [forms]
  (filter #(and (seq? %) (= 'import (first %)))
          (concat forms (library-form forms))))

(defn dependencies
  "What a file's forms depend on, for the analyzer."
  [forms]
  (let [library (library-form forms)]
    {:namespace (when library (library-name (second library)))
     :imports (vec (distinct (mapcat import-clause-libraries (import-forms forms))))
     :load-paths (vec (load-paths forms))
     :libspecs []}))
