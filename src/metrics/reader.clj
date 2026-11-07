(ns metrics.reader
  "Parse Clojure/Jolt source into the normalized model the metric visitors read:
  functions, decision kinds (cyclomatic), the cognitive flow tree, Halstead
  operators/operands, logical LOC, and namespace imports.

  The decision table (cyclomatic complexity):
    - if if-not if-let if-some when when-not when-let when-some -> 1
    - cond condp -> 1 per clause whose test is not :else or true
    - case -> 1 per case except the default
    - and or -> 1 (logical operator)
    - for doseq dotimes while loop -> 1 (loop)
    - catch -> 1 (try itself is not a branch)

  Cognitive complexity: if/when/cond/case/loops/try/catch add 1 plus nesting
  depth.")

;; --- reading -------------------------------------------------------------

(def ^:private scratch-ns
  "The namespace source is read in, so that nothing resolves against the caller's."
  'metrics.read-scratch)

(defn- reading-ns
  "The scratch namespace, with every alias source auto-resolves (`::a/kw`) bound
  to a placeholder.  Metrics only compare keywords, so the real target does not
  matter, and scanning the text also covers aliases made by a top-level
  `require` or `alias`, not just the ns form."
  [source]
  (let [n (create-ns scratch-ns)]
    (binding [*ns* n]
      (refer-clojure)
      (doseq [a (distinct (map second (re-seq #"::([^\s/\"()\[\]{}]+)/" source)))]
        (let [target (symbol (str "metrics.read-alias." a))]
          (create-ns target)
          (alias (symbol a) target))))
    n))

(defn read-forms
  "Read every top-level form of source into a vector.  The source is untrusted
  data: *read-eval* is off, so `#=` is a read error rather than code execution."
  [source]
  (binding [*read-eval* false
            *ns* (reading-ns source)]
    (with-in-str source
      (loop [forms []]
        (let [form (read {:eof ::eof} *in*)]
          (if (= ::eof form)
            forms
            (recur (conj forms form))))))))

;; --- the decision table --------------------------------------------------

(def if-forms '#{if if-not if-let if-some when when-not when-let when-some})
(def loop-forms '#{for doseq dotimes while loop})
(def logical-forms '#{and or})

(defn- defn-name [form]
  (when (and (seq? form) (symbol? (first form)))
    (let [head (first form)
          args (rest form)]
      (when (#{'defn 'defn- 'defmacro} head)
        (first args)))))

(defn- defn-body [form]
  (let [args (rest form)]
    (if (vector? (second args))
      (drop 2 args)
      (drop 3 args))))

(defn functions
  "The defn/defn-/defmacro forms of a file: [{:name .. :line .. :body ..}]."
  [forms]
  (for [form forms
        :let [name (defn-name form)]
        :when name]
    {:name name
     :line (:line (meta form) 0)
     :body (vec (defn-body form))}))

(defn namespace-name
  "The name a file's ns form declares, or nil."
  [forms]
  (let [ns-form (first (filter #(and (seq? %) (= 'ns (first %))) forms))]
    (when (and ns-form (symbol? (second ns-form)))
      (second ns-form))))

(defn imports
  "The namespaces a file requires or imports, from its ns form."
  [forms]
  (let [ns-form (first (filter #(and (seq? %) (= 'ns (first %))) forms))]
    (if-not ns-form
      []
      (vec (distinct
            (for [clause (rest ns-form)
                  :when (and (seq? clause) (#{:require :use :import} (first clause)))
                  spec (rest clause)
                  :let [sym (cond (symbol? spec) spec
                                  (or (vector? spec) (seq? spec)) (first spec))]
                  :when sym]
              sym))))))

;; --- decision kinds (cyclomatic) -----------------------------------------

(defn- cond-decision-count [args]
  (let [tests (take-nth 2 args)
        n (count tests)]
    (if (and (pos? n) (or (= :else (last tests)) (= true (last tests))))
      (dec n)
      n)))

(defn- case-decision-count [args]
  (quot (dec (count args)) 2))

(defn decision-kinds
  "The decision points of form, one keyword per branch, walked depth-first."
  [form]
  (cond
    (not (seq? form)) []
    :else
    (let [head (first form)
          args (rest form)]
      (cond
        (contains? if-forms head) (into [:if] (mapcat decision-kinds args))
        (contains? loop-forms head) (into [:loop] (mapcat decision-kinds args))
        (contains? logical-forms head) (into [:logical] (mapcat decision-kinds args))
        (= 'cond head) (into (repeat (cond-decision-count args) :if) (mapcat decision-kinds args))
        (= 'condp head) (into (repeat (max 0 (dec (count (drop 2 args)))) :if) (mapcat decision-kinds args))
        (= 'case head) (into (repeat (case-decision-count args) :if) (mapcat decision-kinds args))
        (= 'catch head) (into [:catch] (mapcat decision-kinds (drop 2 args)))
        :else (mapcat decision-kinds args)))))

;; --- cognitive flow tree -------------------------------------------------

(declare flow-tree)

(defn- cons-tree
  "Wrap already-mapped flow trees in a cons chain [:Seq h t], ending in [:Leaf]."
  [mapped]
  (if (seq mapped)
    [:Seq (first mapped) (cons-tree (rest mapped))]
    [:Leaf]))

(defn- seq-tree
  "The flow tree of a sequence of raw forms."
  [forms]
  (cons-tree (map flow-tree forms)))

(defn flow-tree
  "The cognitive tree of form: [:Leaf] | [:Seq h t] | [:If t th e] | [:Else x] | [:Nest b]."
  [form]
  (cond
    (seq? form)
    (let [head (first form)
          args (rest form)]
      (cond
        (contains? if-forms head)
        (let [test (first args)
              then (second args)
              else-form (nth args 2 nil)]
          [:If (flow-tree test)
               (flow-tree then)
               (if (nil? else-form) [:Leaf] [:Else (flow-tree else-form)])])

        (or (contains? loop-forms head) (#{'cond 'condp 'case} head) (= 'try head))
        [:Nest (seq-tree args)]

        (= 'catch head)
        [:Nest (seq-tree (drop 2 args))]

        :else
        (seq-tree args)))

    (vector? form)
    (seq-tree form)

    :else [:Leaf]))

;; --- Halstead tokens -----------------------------------------------------

(defn- halstead* [acc form]
  (cond
    (list? form) (reduce halstead* (update acc :operators conj (str (first form))) (rest form))
    (symbol? form) (update acc :operands conj (str form))
    (or (number? form) (string? form) (keyword? form) (char? form)
        (boolean? form) (nil? form))
    (update acc :operands conj (str form))
    (vector? form) (reduce halstead* acc form)
    :else acc))

(defn halstead-tokens
  "Halstead operators (the head of each list) and operands (literals and symbols)."
  [form]
  (halstead* {:operators [] :operands []} form))

;; --- logical LOC ---------------------------------------------------------

(defn form-count
  "The logical LOC of a scope: the number of list forms in it, nested included."
  [form]
  (if (seq? form)
    (inc (reduce + 0 (map form-count (rest form))))
    0))

;; --- the whole file ------------------------------------------------------

(defn analyze-file
  "Parse source into the per-file model (no line-based LOC; see metrics.core)."
  [path source]
  (let [forms (read-forms source)
        fns (functions forms)]
    {:path path
     :language :clojure
     :namespace (namespace-name forms)
     :imports (imports forms)
     :functions
     (for [f fns
           :let [body (:body f)
                 tokens (reduce halstead* {:operators [] :operands []} body)]]
       {:name (:name f)
        :line (:line f)
        :lloc (reduce + 0 (map form-count body))
        :decision-kinds (mapcat decision-kinds body)
        :flow (seq-tree body)
        :operators (:operators tokens)
        :operands (:operands tokens)})
     :top-level-decision-kinds
     (mapcat decision-kinds (remove #(and (seq? %) (defn-name %)) forms))}))
