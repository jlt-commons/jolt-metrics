(ns metrics.scheme
  "The Scheme language adapter: everything the shared pipeline needs to analyze
  Scheme, for Chez and Gambit.  See metrics.language for what an adapter is."
  (:require [metrics.scheme.normalize :as normalize]
            [metrics.scheme.reader :as reader]
            [metrics.scheme.scanner :as scanner]))

(defn- parse [source]
  (let [forms (reader/read-forms source)]
    {:forms (normalize/normalize forms)
     :dependencies {:namespace nil :imports [] :libspecs []}}))

(def language
  ;; a Scheme runtime's names are reached from emitted code and strings, so a name
  ;; nothing mentions may well be in use, and :unused-public? is off
  {:id :scheme
   :extensions #{"ss" "scm" "sls" "sps"}
   :globs ["**.ss" "**.scm" "**.sls" "**.sps"]
   :parse parse
   :scan scanner/scan
   :unused-public? false})
