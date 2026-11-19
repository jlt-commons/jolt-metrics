;; Adapted from clj-kondo.impl.sarif in clj-kondo
;; (https://github.com/clj-kondo/clj-kondo), Copyright (c) Michiel Borkent,
;; distributed under the Eclipse Public License 1.0.
(ns metrics.sarif
  "The report as a SARIF 2.1.0 log, for code scanning in CI: the lint findings,
  every rule a queue entry broke, and the cleanup candidates as notes.  See
  https://github.com/microsoft/sarif-tutorials/blob/main/docs/1-Introduction.md"
  (:require [clojure.data.json :as json]
            [metrics.lint :as lint]
            [metrics.report :as report]))

(def ^:private help-uri "https://github.com/jlt-commons/jolt-metrics#metrics")

(def ^:private queue-rules
  "The rules a queue entry can break."
  [:cyclomatic :cognitive :nesting :lines :params :maintainability :hotspot
   :file-lines :efferent])

(def ^:private cleanup-rules [:unused-require :unused-private :unreferenced-public])

(defn- sarif-level [level]
  (case level
    :error "error"
    :warning "warning"
    :info "note"
    :off "none"))

(defn- band-level [band]
  (if (= :critical band) "error" "warning"))

(defn- location
  "Where a result is: a path, the line it starts on, and optionally its column
  and end line."
  [path line {:keys [column end-line]}]
  [{"physicalLocation"
    {"artifactLocation" {"uri" path}
     "region" (cond-> {"startLine" (max 1 (or line 1))}
                column (assoc "startColumn" column)
                end-line (assoc "endLine" end-line))}}])

(defn- result-of [rule level text locations]
  {"ruleId" (name rule) "level" level "message" {"text" text} "locations" locations})

(defn- lint-result [{:keys [path line column rule level message]}]
  (result-of rule (sarif-level level) message (location path line {:column column})))

(defn- violation-result [{entry-name :name :keys [path line end-line]} {:keys [rule value limit band]}]
  (result-of rule (band-level band)
             (str entry-name ": " (name rule) " is " (report/fmt value) ", limit " (report/fmt limit))
             (location path line {:end-line end-line})))

(defn- cleanup-results [{:keys [unused-requires unused-private unused-public]}]
  (concat
   (for [{:keys [path lib]} unused-requires]
     (result-of :unused-require "note" (str "Unused require: " lib) (location path 1 nil)))
   (for [{:keys [path name line]} unused-private]
     (result-of :unused-private "note" (str "Unused private function: " name) (location path line nil)))
   (for [{:keys [path name line]} unused-public]
     (result-of :unreferenced-public "note" (str "Unreferenced public function: " name)
                (location path line nil)))))

(defn- rules
  "Every rule a result can name, with its default level."
  []
  (concat
   (for [[rule level] (sort-by key lint/default-levels)]
     {"id" (name rule) "helpUri" help-uri
      "defaultConfiguration" {"enabled" (not= :off level) "level" (sarif-level level)}})
   (for [rule (concat queue-rules cleanup-rules)]
     {"id" (name rule) "helpUri" help-uri})))

(defn report
  "The SARIF log for an analysis result, as data with string keys."
  [{:keys [lints queue cleanup]}]
  {"version" "2.1.0"
   "$schema" "https://docs.oasis-open.org/sarif/sarif/v2.1.0/errata01/os/schemas/sarif-schema-2.1.0.json"
   "runs" [{"tool" {"driver" {"name" "jolt-metrics"
                              "informationUri" "https://github.com/jlt-commons/jolt-metrics"
                              "rules" (vec (rules))}}
            "results" (vec (concat (map lint-result lints)
                                   (for [e queue, v (:violations e)] (violation-result e v))
                                   (cleanup-results cleanup)))}]})

(defn render
  "The SARIF log as JSON text."
  [result]
  (json/write-str (report result) :escape-slash false))
