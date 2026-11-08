(ns metrics.report
  "Render project metrics as a Markdown report: first what to fix, then the
  numbers behind it."
  (:require [clojure.string :as str]
            [metrics.entropy :as entropy]
            [metrics.queue :as queue]))

(def default-top
  "How many queue entries to show; EDN output always has them all."
  25)

(defn fmt
  "A number for display: integer when exact, one decimal otherwise; nil for absent."
  [x]
  (cond
    (nil? x) "-"
    (integer? x) (str x)
    :else (let [d (double x)
                r (/ (Math/round (* 10.0 d)) 10.0)]
            (str r))))

(defn- cell
  "Text for a table cell: a | would end the cell early, so it is escaped."
  [s]
  (str/replace (str s) "|" "\\|"))

;; --- the queue -----------------------------------------------------------

(defn- plural [n noun]
  (str n " " noun (when (not= "1" (str n)) "s")))

(def ^:private metric-labels
  [[:cyclomatic #(str "CC " %)]
   [:cognitive #(str "cognitive " %)]
   [:nesting #(str "nesting " %)]
   [:lines #(plural % "line")]
   [:params #(plural % "param")]
   [:maintainability #(str "MI " %)]])

(defn- function-figures
  "A function's numbers, in bold where they broke a rule."
  [{:keys [metrics violations]}]
  (let [broken (set (map :rule violations))]
    (apply str
           (interpose
            ", "
            (for [[k label] metric-labels]
              (let [text (label (fmt (get metrics k)))]
                (if (contains? broken k) (str "**" text "**") text)))))))

(defn- file-figure [{:keys [rule value]}]
  (case rule
    :hotspot (str "**risk " (fmt value) "**")
    :file-lines (str "**" value " source lines**")
    :efferent (str "**" value " dependencies**")))

(defn- file-figures [{:keys [violations]}]
  (apply str (interpose ", " (map file-figure violations))))

(defn- location [{:keys [kind path line end-line]}]
  (if (= :file kind)
    ""
    (str " (" path ":" line "-" end-line ")")))

(defn- changes-note [{:keys [changes]}]
  (if (and changes (pos? changes))
    (str ", " (plural changes "change"))
    ""))

(defn- queue-line [i e]
  (str (inc i) ". **" (name (:severity e)) "** `" (:name e) "`" (location e) ": "
       (if (= :file (:kind e)) (file-figures e) (function-figures e))
       (changes-note e) ". "
       (apply str (interpose " " (:actions e)))
       "\n"))

(defn render-queue
  "The ranked work list, capped at top."
  [entries top]
  (str "## Refactoring queue\n\n"
       (if (seq entries)
         (str (apply str (map-indexed queue-line (take top entries)))
              (when (> (count entries) top)
                (str "\nand " (- (count entries) top) " more; use `--format edn` for all.\n")))
         "_nothing over the thresholds_\n")))

;; --- hotspots ------------------------------------------------------------

(defn render-hotspots
  "The files both complex and changing, or nothing when there is no history."
  [{:keys [changes days hotspots]}]
  (when changes
    (str "## Hotspots\n\n"
         "Complex files that changed at least " queue/min-hotspot-changes
         " times in the last " days " days.\n\n"
         (if (seq hotspots)
           (str "| File | Changes | Cyclomatic | Risk |\n|---|---|---|---|\n"
                (apply str (for [{:keys [path changes complexity risk]} hotspots]
                             (str "| " (cell path) " | " changes " | " complexity " | " (fmt risk) " |\n"))))
           "_none_\n")
         "\n")))

;; --- unusual changes -----------------------------------------------------

(defn signed
  "A change for display, with its sign: +6, -2.5."
  [x]
  (str (when (pos? x) "+") (fmt x)))

(def unusual-intro
  "What the unusual changes section shows, said once for every format."
  (str "Commits whose change to the code's information content, the entropy of "
       "its words in bits, was more than " (fmt entropy/z-limit)
       " standard deviations from the commits before them. Usually a feature "
       "landing, a removal or imported code: worth a closer review."))

(defn render-unusual
  "The unusual changes, or nothing when there is no history."
  [{:keys [unusual-changes]}]
  (when-let [{:keys [commits changes]} unusual-changes]
    (str "## Unusual changes\n\n"
         unusual-intro " Of the last " (plural commits "commit")
         " that changed analyzed code, tests aside.\n\n"
         (if (seq changes)
           (str "| Commit | Date | Entropy change | z | Files | Subject |\n"
                "|---|---|---|---|---|---|\n"
                (apply str (for [{:keys [sha date delta z files subject]} changes]
                             (str "| " (subs sha 0 (min 8 (count sha))) " | " date
                                  " | " (signed delta) " | " (fmt z)
                                  " | " (count files) " | " (cell subject) " |\n"))))
           "_none_\n")
         "\n")))

;; --- cleanup -------------------------------------------------------------

(defn- capped-lines [lines]
  (str (apply str (take default-top lines))
       (when (> (count lines) default-top)
         (str "\nand " (- (count lines) default-top) " more; use `--format edn` for all.\n"))))

(defn- require-line [{:keys [path lib alias refers]}]
  (str "- `" path "`: `" lib "`"
       (when alias (str " (alias `" alias "`)"))
       (when refers (str " (refers " (apply str (interpose ", " (map #(str "`" % "`") refers))) ")"))
       "\n"))

(defn- function-line [{:keys [path name line]}]
  (str "- `" name "` (" path ":" line ")\n"))

(defn- cleanup-part [title lines]
  (when (seq lines)
    (str "### " title "\n\n" (capped-lines lines) "\n")))

(defn render-cleanup
  "What looks unused.  Names are matched conservatively, but runtime lookups and
  callers outside the analyzed files can't be seen."
  [{:keys [unused-requires unused-private unused-public]}]
  (str "## Cleanup candidates\n\n"
       (if (or (seq unused-requires) (seq unused-private) (seq unused-public))
         (str "No mention of these was found in the analyzed files. Runtime lookups and "
              "outside callers can't be seen, so check public functions before removing them.\n\n"
              (cleanup-part "Unused requires" (map require-line unused-requires))
              (cleanup-part "Unused private functions" (map function-line unused-private))
              (cleanup-part "Unreferenced public functions" (map function-line unused-public)))
         "_none_\n\n")))

;; --- the numbers ---------------------------------------------------------

(defn render-errors
  "The files the metrics do not cover, or nothing when every file was read."
  [errors]
  (when (seq errors)
    (str "## Files not analyzed\n\n"
         (apply str (for [{:keys [path error]} (sort-by :path errors)]
                      (str "- `" path "`: " error "\n")))
         "\n")))

(defn render-summary [summary entries]
  (str "## Summary\n\n"
       "| Metric | Value |\n|---|---|\n"
       (str "| Critical findings | " (count (filter #(= :critical (:severity %)) entries)) " |\n")
       (str "| Warnings | " (count (filter #(= :warning (:severity %)) entries)) " |\n")
       (str "| Files | " (:files summary) " |\n")
       (str "| Files not analyzed | " (:unreadable summary) " |\n")
       (str "| Lines of code | " (:loc summary) " |\n")
       (str "| Source lines | " (:sloc summary) " |\n")
       (str "| Comment lines | " (:cloc summary) " |\n")
       (str "| Functions | " (:functions summary) " |\n")
       (str "| Total cyclomatic complexity | " (:cyclomatic summary) " |\n")
       (str "| Average cyclomatic complexity per function | " (fmt (:avg-cyclomatic summary)) " |\n")
       (str "| Total cognitive complexity | " (:cognitive summary) " |\n")
       (str "| Average maintainability index (comment-free) | " (fmt (:avg-maintainability summary)) " |\n")
       (str "| Average instability | " (fmt (:avg-instability summary)) " |\n")))

(defn render-files [files]
  (str "## Files\n\n"
       "| File | LOC | SLOC | Cyclomatic | Cognitive | Halstead V | Maintainability | Instability |\n"
       "|---|---|---|---|---|---|---|---|\n"
       (apply str
              (for [f (sort-by :path files)]
                (str "| " (cell (:path f))
                     " | " (get-in f [:loc :loc])
                     " | " (get-in f [:loc :sloc])
                     " | " (:cyclomatic f)
                     " | " (:cognitive f)
                     " | " (fmt (:volume (:halstead f)))
                     " | " (fmt (:maintainability-index-without-comments f))
                     " | " (fmt (:instability f))
                     " |\n")))))

(defn render-architecture [files edges]
  (str "## Architecture\n\n"
       "### Coupling\n\n"
       "| File | Efferent (Ce) | Afferent (Ca) | Instability | Band |\n"
       "|---|---|---|---|---|\n"
       (apply str
              (for [f (sort-by :path files)]
                (str "| " (cell (:path f))
                     " | " (:efferent-coupling f)
                     " | " (:afferent-coupling f)
                     " | " (fmt (:instability f))
                     " | " (name (:instability-band f))
                     " |\n")))
       "\n### Dependencies\n\n"
       (if (seq edges)
         (apply str (for [[from to] (sort edges)] (str "- `" from "` -> `" to "`\n")))
         "_no internal dependencies_\n")))

(defn render-thresholds
  "What the queue's severities mean."
  []
  (let [row (fn [label [_ ok warning]]
              (str "| " label " | > " ok " | > " warning " |\n"))
        {:keys [cyclomatic cognitive nesting lines params]} queue/function-limits
        {:keys [file-lines efferent]} queue/file-limits]
    (str "## Thresholds\n\n"
         "| Rule | Warning | Critical |\n|---|---|---|\n"
         (row "Cyclomatic complexity" cyclomatic)
         (row "Cognitive complexity" cognitive)
         (row "Nesting depth" nesting)
         (row "Function source lines" lines)
         (row "Function parameters" params)
         "| Maintainability index (comment-free) | <= 38 | < 10 |\n"
         (row "File source lines" file-lines)
         (row "File dependencies" efferent)
         (str "| Hotspot risk | >= " (:warning queue/hotspot-limits)
              " | >= " (:critical queue/hotspot-limits) " |\n"))))

(defn render
  "The full Markdown report for an analysis result."
  ([result] (render result nil))
  ([{:keys [files summary edges errors queue cleanup] :as result} {:keys [top]}]
   (str "# Code Metrics Report\n\n"
        (render-summary summary queue)
        "\n"
        (render-errors errors)
        (render-queue queue (or top default-top))
        "\n"
        (render-hotspots result)
        (render-unusual result)
        (render-cleanup cleanup)
        (render-files files)
        "\n"
        (render-architecture files edges)
        "\n"
        (render-thresholds))))
