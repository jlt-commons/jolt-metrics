(ns metrics.report
  "Render project metrics as a Markdown report: first what to fix, then the
  numbers behind it."
  (:require [clojure.string :as str]
            [metrics.entropy :as entropy]
            [metrics.thresholds :as thresholds]))

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

(defn limits-of
  "The thresholds a result was judged by: its own, or the defaults for a result
  made before it carried them."
  [result]
  (or (:thresholds result) thresholds/defaults))

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

(defn reach-figure
  "How many functions depend on this one, when any do."
  [{:keys [reach]}]
  (when (and reach (pos? reach)) (str ", reach " reach)))

(defn- function-figures
  "A function's numbers, in bold where they broke a rule."
  [{:keys [metrics violations]}]
  (let [broken (set (map :rule violations))]
    (str (apply str
                (interpose
                 ", "
                 (for [[k label] metric-labels]
                   (let [text (label (fmt (get metrics k)))]
                     (if (contains? broken k) (str "**" text "**") text)))))
         (reach-figure metrics))))

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

(defn hotspots-intro
  "What the hotspots section shows, said once for every format."
  [limits days]
  (str "Complex files that changed at least " (get-in limits [:hotspot :min-changes])
       " times in the last " days " days."))

(defn render-hotspots
  "The files both complex and changing, or nothing when there is no history."
  [{:keys [changes days hotspots] :as result}]
  (when changes
    (str "## Hotspots\n\n"
         (hotspots-intro (limits-of result) days) "\n\n"
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

(defn unusual-intro
  "What the unusual changes section shows, said once for every format."
  [limits]
  (str "Commits whose change to the code's information content, the entropy of "
       "its words in bits, was more than " (fmt (get-in limits [:unusual :z]))
       " standard deviations from the commits before them. Usually a feature "
       "landing, a removal or imported code: worth a closer review."))

(defn render-unusual
  "The unusual changes, or nothing when there is no history."
  [{:keys [unusual-changes] :as result}]
  (when-let [{:keys [commits changes]} unusual-changes]
    (str "## Unusual changes\n\n"
         (unusual-intro (limits-of result)) " Of the last " (plural commits "commit")
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

(defn unusual-files-intro
  "What the unusual files section shows, said once for every format."
  [limits]
  (str "Files whose words are unusually surprising for this project: the bits each "
       "word takes, on average, given the words of the other files, more than "
       (fmt (get-in limits [:unusual :z])) " standard deviations above the files' mean. Often "
       "vendored, generated or example code, or a concern that belongs elsewhere. "
       "Tests aside, and files under " entropy/min-words " words."))

(defn render-unusual-files
  "The files whose vocabulary stands out from the rest."
  [{:keys [unusual-files] :as result}]
  (str "## Unusual files\n\n"
       (unusual-files-intro (limits-of result)) "\n\n"
       (if (seq unusual-files)
         (str "| File | Surprisal (bits a word) | z | Words |\n|---|---|---|---|\n"
              (apply str (for [{:keys [path surprisal z words]} unusual-files]
                           (str "| " (cell path) " | " (fmt surprisal) " | " (fmt z)
                                " | " words " |\n"))))
         "_none_\n")
       "\n"))

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
       (str "| Average instability | " (fmt (:avg-instability summary)) " |\n")
       (str "| Average entropy per file | " (fmt (:avg-entropy summary)) " |\n")
       (str "| Average redundancy per file | " (fmt (:avg-redundancy summary)) " |\n")
       (str "| Near-duplicate pairs | " (:duplicates summary) " |\n")
       (str "| Abstraction opportunities | " (or (:abstractions summary) "skipped") " |\n")))

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

(defn threshold-rows
  "[rule warning critical] for each limit the queue judges by, as text."
  [limits]
  (let [over (fn [label k]
               (let [{:keys [warning critical]} (get limits k)]
                 [label (str "> " (fmt warning)) (str "> " (fmt critical))]))
        {mi-warning :warning mi-critical :critical} (:maintainability limits)
        {:keys [warning critical min-changes]} (:hotspot limits)]
    [(over "Cyclomatic complexity" :cyclomatic)
     (over "Cognitive complexity" :cognitive)
     (over "Nesting depth" :nesting)
     (over "Function source lines" :lines)
     (over "Function parameters" :params)
     ["Maintainability index (comment-free)" (str "<= " (fmt mi-warning)) (str "< " (fmt mi-critical))]
     (over "File source lines" :file-lines)
     (over "File dependencies" :efferent)
     [(str "Hotspot risk (at least " min-changes " changes)")
      (str ">= " (fmt warning)) (str ">= " (fmt critical))]]))

(defn render-thresholds
  "What the queue's severities mean, by the limits the result was judged by."
  [result]
  (str "## Thresholds\n\n"
       "| Rule | Warning | Critical |\n|---|---|---|\n"
       (apply str (for [[label w c] (threshold-rows (limits-of result))]
                    (str "| " label " | " w " | " c " |\n")))))

;; --- near-duplicates -------------------------------------------------------

(defn duplicates-intro
  "What the near-duplicates section shows, said once for every format."
  [limits]
  (str "Pairs of definitions that are near-copies: once locals are renamed and "
       "strings blanked, compressing the two together takes little more than "
       "compressing either alone. The distance is their normalized compression "
       "distance, 0 for the same code; pairs at or under "
       (get-in limits [:duplicates :max-distance])
       " are listed, closest first. Extract what they share, or keep one. Tests "
       "aside, and definitions under " (get-in limits [:duplicates :min-tokens]) " tokens."))

(defn- place [{:keys [path name line end-line]}]
  (str "`" name "` (" path ":" line "-" end-line ")"))

(defn render-duplicates
  "The near-duplicate pairs, the closest top of them."
  [{:keys [duplicates] :as result} top]
  (str "## Near-duplicates\n\n"
       (duplicates-intro (limits-of result)) "\n\n"
       (if (seq duplicates)
         (str "| Distance | Definition | Near-copy |\n|---|---|---|\n"
              (apply str (for [{:keys [distance a b]} (take top duplicates)]
                           (str "| " (format "%.2f" (double distance))
                                " | " (cell (place a)) " | " (cell (place b)) " |\n")))
              (when (> (count duplicates) top)
                (str "\n" (- (count duplicates) top) " more in the EDN and JSON output.\n")))
         "_none_\n")
       "\n"))

;; --- abstraction opportunities ---------------------------------------------

(defn abstractions-intro
  "What the abstraction opportunities section shows, said once for every format."
  [limits]
  (str "Code shapes that recur often enough for one function to replace them. "
       "Each pattern is the most specific code its uses share, with ?a, ?b and so "
       "on where they differ; a variable in a binding position is a local of the "
       "new function, not a parameter. Saves is roughly how many syntax nodes "
       "extracting it removes: every use becomes a call, less the new definition. "
       "Threading macros, when, when-not and if-not are seen through, so sugared and "
       "plain code match. Tests aside; patterns of at least "
       (get-in limits [:abstractions :min-size]) " nodes with at most "
       (get-in limits [:abstractions :max-arity]) " parameters."))

(defn sites-text
  "Where an abstraction is used: the first three definitions, then how many more."
  [sites]
  (str (str/join ", " (for [{:keys [path name line]} (take 3 sites)]
                        (str name " (" path ":" line ")")))
       (when (> (count sites) 3) (str " and " (- (count sites) 3) " more"))))

(defn render-abstractions
  "The abstraction opportunities, the top of them by what they save."
  [{:keys [abstractions] :as result} top]
  (str "## Abstraction opportunities\n\n"
       (abstractions-intro (limits-of result)) "\n\n"
       (cond
         (nil? abstractions) "Not looked for (`--no-abstractions`).\n"
         (empty? abstractions) "_none_\n"
         :else
         (str "| Saves | Uses | Params | Pattern | Where |\n|---|---|---|---|---|\n"
              (apply str (for [{:keys [saving uses arity pattern sites]} (take top abstractions)]
                           (str "| " saving " | " uses " | " arity
                                " | " (cell (str "`" (pr-str pattern) "`"))
                                " | " (cell (sites-text sites)) " |\n")))
              (when (> (count abstractions) top)
                (str "\n" (- (count abstractions) top) " more in the EDN and JSON output.\n"))))
       "\n"))

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
        (render-unusual-files result)
        (render-duplicates result (or top default-top))
        (render-abstractions result (or top default-top))
        (render-cleanup cleanup)
        (render-files files)
        "\n"
        (render-architecture files edges)
        "\n"
        (render-thresholds result))))
