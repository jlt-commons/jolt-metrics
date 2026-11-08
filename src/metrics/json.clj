(ns metrics.json
  "The lean JSON report, for tools and LLMs: what to act on, in a shape that
  stays put.  Keys are snake_case and always present, and schema-version changes
  when the shape does.  EDN output is the full result; this is a projection of it."
  (:require [clojure.data.json :as json]
            [clojure.string :as str]))

(def schema-version
  "Bumped whenever a key is removed or changes meaning."
  1)

(defn- clean-number
  "A number JSON can carry: integers as they are, others rounded to 3 decimals,
  and nil for NaN and the infinities, which JSON has no way to write."
  [x]
  (if (integer? x)
    x
    (let [d (double x)]
      (when-not (or (Double/isNaN d) (Double/isInfinite d))
        (/ (Math/round (* 1000.0 d)) 1000.0)))))

(defn- clean
  "v with keywords as snake_case strings and every number made JSON-safe."
  [v]
  (cond
    (keyword? v) (str/replace (name v) "-" "_")
    (number? v) (clean-number v)
    (map? v) (into {} (map (fn [[k x]] [k (clean x)])) v)
    (sequential? v) (mapv clean v)
    :else v))

(defn- summary [{:keys [summary queue]}]
  (let [severities (frequencies (map :severity queue))]
    {"critical" (get severities :critical 0)
     "warnings" (get severities :warning 0)
     "files" (:files summary)
     "files_not_analyzed" (:unreadable summary)
     "loc" (:loc summary)
     "sloc" (:sloc summary)
     "cloc" (:cloc summary)
     "functions" (:functions summary)
     "cyclomatic_total" (:cyclomatic summary)
     "cyclomatic_avg" (:avg-cyclomatic summary)
     "cognitive_total" (:cognitive summary)
     "maintainability_avg" (:avg-maintainability summary)
     "instability_avg" (:avg-instability summary)}))

(defn- violation [{:keys [rule value limit band]}]
  {"rule" rule "value" value "limit" limit "band" band})

(defn- function-metrics [metrics]
  (when metrics
    (into {} (map (fn [k] [(name k) (get metrics k)]))
          [:cyclomatic :cognitive :nesting :lines :params :maintainability])))

(defn- finding [i e]
  {"rank" (inc i)
   "severity" (:severity e)
   "kind" (:kind e)
   "name" (:name e)
   "path" (:path e)
   "line" (:line e)
   "end_line" (:end-line e)
   "changes" (:changes e)
   "score" (:score e)
   "metrics" (function-metrics (:metrics e))
   "violations" (mapv violation (:violations e))
   "actions" (vec (:actions e))})

(defn- hotspots [{:keys [changes hotspots]}]
  (when changes
    (mapv (fn [{:keys [path changes complexity risk]}]
            {"path" path "changes" changes "complexity" complexity "risk" risk})
          hotspots)))

(defn- unusual-changes [{:keys [unusual-changes]}]
  (when-let [{:keys [commits changes]} unusual-changes]
    {"commits" commits
     "changes" (mapv (fn [{:keys [sha date subject delta z files]}]
                       {"commit" sha "date" date "subject" subject
                        "delta" delta "z" z "files" (vec files)})
                     changes)}))

(defn- unused-require [{:keys [path lib alias refers]}]
  {"path" path "lib" lib "alias" alias "refers" (vec refers)})

(defn- named-place [{:keys [path name line]}]
  {"path" path "name" name "line" line})

(defn- cleanup [{:keys [unused-requires unused-private unused-public]}]
  {"unused_requires" (mapv unused-require unused-requires)
   "unused_private" (mapv named-place unused-private)
   "unreferenced_public" (mapv named-place unused-public)})

(defn- file-row [f]
  {"path" (:path f)
   "language" (:language f)
   "namespace" (some-> (:namespace f) str)
   "loc" (get-in f [:loc :loc])
   "sloc" (get-in f [:loc :sloc])
   "cloc" (get-in f [:loc :cloc])
   "cyclomatic" (:cyclomatic f)
   "cognitive" (:cognitive f)
   "maintainability" (:maintainability-index-without-comments f)
   "efferent" (:efferent-coupling f)
   "afferent" (:afferent-coupling f)
   "instability" (:instability f)
   "instability_band" (:instability-band f)
   "changes" (:changes f)})

(defn report
  "The lean report for an analysis result, as data with string keys."
  [result]
  (clean
   {"tool" "jolt-metrics"
    "schema_version" schema-version
    "summary" (summary result)
    "findings" (vec (map-indexed finding (:queue result)))
    "hotspots" (hotspots result)
    "unusual_changes" (unusual-changes result)
    "cleanup" (cleanup (:cleanup result))
    "errors" (mapv (fn [{:keys [path error]}] {"path" path "error" error}) (:errors result))
    "files" (mapv file-row (sort-by :path (:files result)))
    "dependencies" (mapv (fn [[from to]] {"from" from "to" to}) (sort (:edges result)))}))

(defn render
  "The lean report as JSON text.  Slashes are left as they are, so paths read
  plainly; HTML that embeds this escapes `<` itself."
  [result]
  (json/write-str (report result) :escape-slash false))
