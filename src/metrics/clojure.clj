(ns metrics.clojure
  "The Clojure and Jolt language adapter.  See metrics.language for what an adapter
  is."
  (:require [metrics.reader :as reader]
            [metrics.scanner :as scanner]))

(defn- parse [source]
  (let [forms (reader/read-forms source)]
    {:forms forms
     :dependencies (reader/dependencies forms)}))

(def language
  {:id :clojure
   :extensions #{"clj" "cljs" "cljc" "jolt"}
   :globs ["**.clj*" "**.jolt"]
   :parse parse
   :scan scanner/scan
   :unused-public? true})
