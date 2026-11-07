(ns metrics.report
  "Render project metrics as a Markdown report, readable by a human and an agent.")

(defn- fmt
  "A number for display: integer when exact, one decimal otherwise; nil for absent."
  [x]
  (cond
    (nil? x) "-"
    (integer? x) (str x)
    :else (let [d (double x)
                r (/ (Math/round (* 10.0 d)) 10.0)]
            (str r))))

(defn- bar [label n width]
  (let [n (max 0 (min 1 (double n)))]
    (str "`" label "` " (apply str (repeat (int (* n width)) "#")))))

(defn- top-functions [files key n]
  (->> (for [f files, fn (:functions f)]
         [(:name fn) (:path f) (:line fn) (key fn)])
       (sort-by #(- (nth % 3)))
       (take n)))

(defn render-errors
  "The files the metrics do not cover, or nothing when every file was read."
  [errors]
  (when (seq errors)
    (str "## Files not analyzed\n\n"
         (apply str (for [{:keys [path error]} (sort-by :path errors)]
                      (str "- `" path "`: " error "\n")))
         "\n")))

(defn render-summary [summary]
  (str "## Summary\n\n"
       "| Metric | Value |\n|---|---|\n"
       (str "| Files | " (:files summary) " |\n")
       (str "| Files not analyzed | " (:unreadable summary) " |\n")
       (str "| Lines of code | " (:loc summary) " |\n")
       (str "| Source lines | " (:sloc summary) " |\n")
       (str "| Comment lines | " (:cloc summary) " |\n")
       (str "| Functions | " (:functions summary) " |\n")
       (str "| Total cyclomatic complexity | " (:cyclomatic summary) " |\n")
       (str "| Average cyclomatic complexity | " (fmt (:avg-cyclomatic summary)) " |\n")
       (str "| Total cognitive complexity | " (:cognitive summary) " |\n")
       (str "| Average maintainability index | " (fmt (:avg-maintainability summary)) " |\n")
       (str "| Average instability | " (fmt (:avg-instability summary)) " |\n")))

(defn render-files [files]
  (str "## Files\n\n"
       "| File | LOC | SLOC | Cyclomatic | Cognitive | Halstead V | Maintainability | Instability |\n"
       "|---|---|---|---|---|---|---|---|\n"
       (apply str
              (for [f (sort-by :path files)]
                (str "| " (:path f)
                     " | " (get-in f [:loc :loc])
                     " | " (get-in f [:loc :sloc])
                     " | " (:cyclomatic f)
                     " | " (:cognitive f)
                     " | " (fmt (:volume (:halstead f)))
                     " | " (fmt (:maintainability-index f))
                     " | " (fmt (:instability f))
                     " |\n")))))

(defn render-top-offenders [files]
  (let [cc (top-functions files :cyclomatic 10)
        cog (top-functions files :cognitive 10)]
    (str "## Top offenders\n\n"
         "### By cyclomatic complexity\n\n"
         (if (seq cc)
           (apply str (for [[i [name path line v]] (map-indexed vector cc)]
                        (str (inc i) ". `" name "` (" path ":" line ") — " v "\n")))
           "_none_\n")
         "\n### By cognitive complexity\n\n"
         (if (seq cog)
           (apply str (for [[i [name path line v]] (map-indexed vector cog)]
                        (str (inc i) ". `" name "` (" path ":" line ") — " v "\n")))
           "_none_\n"))))

(defn render-architecture [files edges]
  (str "## Architecture\n\n"
       "### Coupling\n\n"
       "| File | Efferent (Ce) | Afferent (Ca) | Instability | Band |\n"
       "|---|---|---|---|---|\n"
       (apply str
              (for [f (sort-by :path files)]
                (str "| " (:path f)
                     " | " (:efferent-coupling f)
                     " | " (:afferent-coupling f)
                     " | " (fmt (:instability f))
                     " | " (name (:instability-band f))
                     " |\n")))
       "\n### Dependencies\n\n"
       (if (seq edges)
         (apply str (for [[from to] (sort edges)] (str "- `" from "` -> `" to "`\n")))
         "_no internal dependencies_\n")))

(defn render
  "The full Markdown report for an analysis result."
  [{:keys [files summary edges errors]}]
  (str "# Code Metrics Report\n\n"
       (render-summary summary)
       "\n"
       (render-errors errors)
       (render-files files)
       "\n"
       (render-top-offenders files)
       "\n"
       (render-architecture files edges)))
