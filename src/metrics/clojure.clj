(ns metrics.clojure
  "The Clojure and Jolt language adapter.  See metrics.language for what an adapter
  is."
  (:require [metrics.reader :as reader]
            [metrics.scanner :as scanner]
            [jolt-lsp.analyze :as lsp-analyze]))

(defn- parse [source]
  (let [forms (reader/read-forms source)]
    {:forms forms
     :dependencies (reader/dependencies forms)}))

(defn- index-analysis
  "What jolt-lsp's analyzer finds in source, for the call-graph index.  Its
  reader is tolerant, but a file it can't take only loses its call-graph numbers."
  [path source]
  (try (lsp-analyze/analyze source path) (catch Throwable _ nil)))

(def language
  {:id :clojure
   :extensions #{"clj" "cljs" "cljc" "jolt"}
   :globs ["**.clj*" "**.jolt"]
   :parse parse
   :scan scanner/scan
   :index index-analysis
   :unused-public? true})
