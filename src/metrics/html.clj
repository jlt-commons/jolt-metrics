(ns metrics.html
  "Render project metrics as one self-contained HTML page: the Markdown report's
  sections as plain tables, styled and scripted inline, with the lean JSON report
  embedded so the file carries its own data.  Nothing is fetched: no fonts, no
  CDN, no links out.  The script only sorts, filters, collapses and downloads, so
  the page reads the same without it."
  (:require [clojure.java.io :as io]
            [clojure.string :as str]
            [metrics.json :as json]
            [metrics.queue :as queue]
            [metrics.report :as report]))

;; --- markup ----------------------------------------------------------------
;; Nodes are hiccup-like vectors.  Every string is escaped on the way out; only
;; text wrapped by `raw` (the stylesheet, the script, the embedded JSON) is not.

(defn- esc [s]
  (str/escape (str s) {\& "&amp;" \< "&lt;" \> "&gt;" \" "&quot;" \' "&#39;"}))

(defn- raw [s] {::raw s})

(def ^:private void-tags #{:meta :input})

(defn- attrs [m]
  (apply str (for [[k v] m :when (some? v)]
               (if (true? v)
                 (str " " (name k))
                 (str " " (name k) "=\"" (esc v) "\"")))))

(declare markup)

(defn- element [[tag & more]]
  (let [x (first more)
        [props children] (if (and (map? x) (not (::raw x))) [x (rest more)] [nil more])
        open (str "<" (name tag) (attrs props) ">")]
    (if (contains? void-tags tag)
      open
      (str open (apply str (map markup children)) "</" (name tag) ">"))))

(defn- markup [node]
  (cond
    (nil? node) ""
    (::raw node) (::raw node)
    (vector? node) (element node)
    (seq? node) (apply str (map markup node))
    :else (esc node)))

;; --- small pieces ----------------------------------------------------------

(defn- num-cell
  "A numeric cell: rounded for reading, exact in data-value for sorting."
  [x]
  [:td {:data-value (when x (str (double x)))} (report/fmt x)])

(defn- table
  "A table with a header row; rows are vectors of cells."
  [opts headers rows]
  [:div {:class "scroll"}
   [:table opts
    [:thead [:tr (for [h headers] [:th h])]]
    [:tbody (for [r rows] (into [:tr] r))]]])

(defn- section [id title & body]
  (into [:section {:id id} [:h2 title]] body))

(defn- more-line [shown total]
  (when (> total shown)
    [:p {:class "note"} "and " (- total shown) " more; use " [:code "--format json"] " for all."]))

(defn- plural
  "n and noun, plural unless n is one; n may already be formatted text."
  [n noun]
  (str n " " noun (when (not= "1" (str n)) "s")))

;; --- summary and errors ----------------------------------------------------

(defn- summary-rows [summary entries]
  (let [count-of (fn [sev] (count (filter #(= sev (:severity %)) entries)))]
    [["Critical findings" (count-of :critical)]
     ["Warnings" (count-of :warning)]
     ["Files" (:files summary)]
     ["Files not analyzed" (:unreadable summary)]
     ["Lines of code" (:loc summary)]
     ["Source lines" (:sloc summary)]
     ["Comment lines" (:cloc summary)]
     ["Functions" (:functions summary)]
     ["Total cyclomatic complexity" (:cyclomatic summary)]
     ["Average cyclomatic complexity per function" (:avg-cyclomatic summary)]
     ["Total cognitive complexity" (:cognitive summary)]
     ["Average maintainability index (comment-free)" (:avg-maintainability summary)]
     ["Average instability" (:avg-instability summary)]]))

(defn- summary-section [summary entries]
  (section "summary" "Summary"
           (table {} ["Metric" "Value"]
                  (for [[label v] (summary-rows summary entries)]
                    [[:td label] (num-cell v)]))))

(defn- errors-section [errors]
  (when (seq errors)
    (section "errors" "Files not analyzed"
             [:ul (for [{:keys [path error]} (sort-by :path errors)]
                    [:li [:code path] ": " error])])))

;; --- the queue -------------------------------------------------------------

(def ^:private metric-labels
  [[:cyclomatic "CC "] [:cognitive "cognitive "] [:nesting "nesting "]
   [:lines nil "line"] [:params nil "param"] [:maintainability "MI "]])

(defn- function-figures [{:keys [metrics violations]}]
  (let [broken (set (map :rule violations))]
    (interpose ", "
               (for [[k prefix noun] metric-labels
                     :let [v (get metrics k)
                           text (if noun (plural (report/fmt v) noun) (str prefix (report/fmt v)))]]
                 (if (contains? broken k) [:strong text] text)))))

(defn- file-figure [{:keys [rule value]}]
  [:strong (case rule
             :hotspot (str "risk " (report/fmt value))
             :file-lines (str value " source lines")
             :efferent (str value " dependencies"))])

(defn- queue-item [{:keys [kind severity name path line end-line changes actions] :as e}]
  [:li {:data-severity (clojure.core/name severity)}
   [:span {:class (str "badge " (clojure.core/name severity))} (clojure.core/name severity)]
   " " [:code name]
   (when-not (= :file kind) (str " (" path ":" line "-" end-line ")"))
   ": " (if (= :file kind) (interpose ", " (map file-figure (:violations e))) (function-figures e))
   (when (and changes (pos? changes)) (str ", " (plural changes "change")))
   ". " [:span {:class "actions"} (str/join " " actions)]])

(defn- severity-controls []
  [:div {:class "controls needs-js"}
   "Show:"
   (for [sev ["critical" "warning"]]
     [:label [:input {:type "checkbox" :class "severity-filter" :value sev :checked true}] " " sev])])

(defn- queue-section [entries top]
  (section "queue" "Refactoring queue"
           (if (seq entries)
             (list (severity-controls)
                   [:ol {:class "queue"} (map queue-item (take top entries))]
                   (more-line top (count entries)))
             [:p [:em "nothing over the thresholds"]])))

;; --- hotspots and cleanup --------------------------------------------------

(defn- hotspots-section [{:keys [changes days hotspots]}]
  (when changes
    (section "hotspots" "Hotspots"
             [:p "Complex files that changed at least " queue/min-hotspot-changes
              " times in the last " days " days."]
             (if (seq hotspots)
               (table {:class "sortable"} ["File" "Changes" "Cyclomatic" "Risk"]
                      (for [{:keys [path changes complexity risk]} hotspots]
                        [[:td path] (num-cell changes) (num-cell complexity) (num-cell risk)]))
               [:p [:em "none"]]))))

(defn- unusual-section [{:keys [unusual-changes]}]
  (when-let [{:keys [commits changes]} unusual-changes]
    (section "unusual" "Unusual changes"
             [:p report/unusual-intro " Of the last " (plural commits "commit")
              " that changed analyzed code, tests aside."]
             (if (seq changes)
               (table {:class "sortable"} ["Commit" "Date" "Entropy change" "z" "Files" "Subject"]
                      (for [{:keys [sha date delta z files subject]} changes]
                        [[:td [:code (subs sha 0 (min 8 (count sha)))]] [:td date]
                         [:td {:data-value (str (double delta))} (report/signed delta)]
                         (num-cell z) (num-cell (count files)) [:td subject]]))
               [:p [:em "none"]]))))

(defn- require-item [{:keys [path lib alias refers]}]
  [:li [:code path] ": " [:code lib]
   (when alias (list " (alias " [:code alias] ")"))
   (when refers (list " (refers " (interpose ", " (map (fn [r] [:code r]) refers)) ")"))])

(defn- function-item [{:keys [path name line]}]
  [:li [:code name] " (" path ":" line ")"])

(defn- cleanup-part [title items]
  (when (seq items)
    (list [:h3 title]
          [:ul (take report/default-top items)]
          (more-line report/default-top (count items)))))

(defn- cleanup-section [{:keys [unused-requires unused-private unused-public]}]
  (section "cleanup" "Cleanup candidates"
           (if (or (seq unused-requires) (seq unused-private) (seq unused-public))
             (list [:p "No mention of these was found in the analyzed files. Runtime lookups and "
                    "outside callers can't be seen, so check public functions before removing them."]
                   (cleanup-part "Unused requires" (map require-item unused-requires))
                   (cleanup-part "Unused private functions" (map function-item unused-private))
                   (cleanup-part "Unreferenced public functions" (map function-item unused-public)))
             [:p [:em "none"]])))

;; --- the numbers -----------------------------------------------------------

(defn- file-cells [f]
  [[:td (:path f)]
   (num-cell (get-in f [:loc :loc]))
   (num-cell (get-in f [:loc :sloc]))
   (num-cell (:cyclomatic f))
   (num-cell (:cognitive f))
   (num-cell (:volume (:halstead f)))
   (num-cell (:maintainability-index-without-comments f))
   (num-cell (:instability f))])

(defn- files-section [files]
  (section "files" "Files"
           [:div {:class "controls needs-js"}
            [:input {:type "search" :id "files-filter" :placeholder "Filter by path"
                     :aria-label "Filter files by path"}]]
           (table {:class "sortable" :id "files-table"}
                  ["File" "LOC" "SLOC" "Cyclomatic" "Cognitive" "Halstead V" "Maintainability" "Instability"]
                  (map file-cells (sort-by :path files)))))

(defn- coupling-cells [f]
  [[:td (:path f)]
   (num-cell (:efferent-coupling f))
   (num-cell (:afferent-coupling f))
   (num-cell (:instability f))
   [:td (some-> (:instability-band f) name)]])

(defn- architecture-section [files edges]
  (section "architecture" "Architecture"
           [:h3 "Coupling"]
           (table {:class "sortable"} ["File" "Efferent (Ce)" "Afferent (Ca)" "Instability" "Band"]
                  (map coupling-cells (sort-by :path files)))
           [:h3 "Dependencies"]
           (if (seq edges)
             [:ul (for [[from to] (sort edges)] [:li [:code from] " -> " [:code to]])]
             [:p [:em "no internal dependencies"]])))

(defn- threshold-rows []
  (let [row (fn [label [_ ok warning]] [label (str "> " ok) (str "> " warning)])
        {:keys [cyclomatic cognitive nesting lines params]} queue/function-limits
        {:keys [file-lines efferent]} queue/file-limits]
    [(row "Cyclomatic complexity" cyclomatic)
     (row "Cognitive complexity" cognitive)
     (row "Nesting depth" nesting)
     (row "Function source lines" lines)
     (row "Function parameters" params)
     ["Maintainability index (comment-free)" "<= 38" "< 10"]
     (row "File source lines" file-lines)
     (row "File dependencies" efferent)
     ["Hotspot risk" (str ">= " (:warning queue/hotspot-limits)) (str ">= " (:critical queue/hotspot-limits))]]))

(defn- thresholds-section []
  (section "thresholds" "Thresholds"
           (table {} ["Rule" "Warning" "Critical"]
                  (for [r (threshold-rows)] (mapv (fn [c] [:td c]) r)))))

;; --- the page --------------------------------------------------------------

(defn- asset
  "A file from resources/metrics/html, which the page inlines."
  [file]
  (let [path (str "metrics/html/" file)]
    (if-let [url (io/resource path)]
      (slurp url)
      (throw (ex-info (str "missing resource: " path) {:path path})))))

(defn- embedded-json
  "The lean JSON with every < escaped, so it can't end its script block."
  [result]
  (str/replace (json/render result) "<" "\\u003c"))

(defn- head []
  [:head
   [:meta {:charset "utf-8"}]
   [:meta {:name "viewport" :content "width=device-width, initial-scale=1"}]
   [:title "Code Metrics Report"]
   [:style (raw (asset "report.css"))]])

(defn- body [{:keys [files summary edges errors queue cleanup] :as result} top]
  [:body
   [:main
    [:header
     [:h1 "Code Metrics Report"]
     [:div {:class "toolbar needs-js"}
      [:button {:type "button" :id "theme-toggle" :title "Colour theme: follows the system until you pick one"}
       "Theme: auto"]
      [:button {:type "button" :id "download-json"} "Download JSON"]]]
    (summary-section summary queue)
    (errors-section errors)
    (queue-section queue top)
    (hotspots-section result)
    (unusual-section result)
    (cleanup-section cleanup)
    (files-section files)
    (architecture-section files edges)
    (thresholds-section)]
   [:script {:type "application/json" :id "metrics-data"} (raw (embedded-json result))]
   [:script (raw (asset "report.js"))]])

(defn render
  "The full HTML report for an analysis result: one file, nothing fetched."
  ([result] (render result nil))
  ([result {:keys [top]}]
   (str "<!doctype html>\n"
        (markup [:html {:lang "en"} (head) (body result (or top report/default-top))])
        "\n")))
