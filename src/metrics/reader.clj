(ns metrics.reader
  "Parse Clojure/Jolt source into the normalized model the metric visitors read:
  functions, decision kinds (cyclomatic), the cognitive flow tree, Halstead
  operators/operands, logical LOC, and namespace imports.

  The decision table (cyclomatic complexity):
    - if if-not if-let if-some when when-not when-let when-some when-first -> 1
    - cond -> 1 per clause; a trailing keyword or true test is the free default
    - condp -> 1 per clause; a lone trailing form is the free default
    - case -> 1 per case except the default
    - cond-> cond->> -> 1 per clause
    - and or -> 1 per operator, so n-1 for n arguments
    - for doseq -> 1 per binding, 1 per :when or :while
    - dotimes while loop -> 1
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

(def two-way-forms '#{if if-not if-let if-some})
(def if-forms (into two-way-forms '#{when when-not when-let when-some when-first}))
(def loop-forms '#{for doseq dotimes while loop})
(def logical-forms '#{and or})
(def cond-threading-forms '#{cond-> cond->>})

;; --- the code in a form --------------------------------------------------
;;
;; Every metric walks forms through code-children, so they all see the same code:
;; collections are code, quoted data and comment forms are not, nor are case
;; constants or a catch's class and binding.

(defn- head-name
  "A list's head symbol, with a clojure.core qualifier removed; nil when the head
  is not a symbol."
  [form]
  (let [h (first form)]
    (when (symbol? h)
      (if (= "clojure.core" (namespace h)) (symbol (name h)) h))))

(defn- case-parts
  "Split a case's args into its expression, constants and result forms."
  [args]
  (let [clauses (rest args)
        pairs (partition 2 clauses)]
    {:expr (first args)
     :constants (map first pairs)
     :results (concat (map second pairs)
                      (when (odd? (count clauses)) [(last clauses)]))}))

(defn- code-children
  "The sub-forms of form that are code."
  [form]
  (cond
    (map? form) (mapcat identity form)
    (or (vector? form) (set? form)) (seq form)
    (seq? form)
    (let [h (head-name form)
          args (rest form)]
      (cond
        (or (= 'quote h) (= 'comment h)) nil
        (= 'case h) (let [{:keys [expr results]} (case-parts args)] (cons expr results))
        (= 'catch h) (drop 2 args)
        h args
        (coll? (first form)) form
        :else args))
    :else nil))

;; --- syntax-quote, read back ---------------------------------------------

(defn- core-call?
  "Is form a call of the clojure.core function named sym?"
  [form sym]
  (and (seq? form) (= (symbol "clojure.core" (name sym)) (first form))))

(defn- written-symbol
  "A template's symbol as it was written: no core or scratch qualifier."
  [sym]
  (if (#{"clojure.core" (name scratch-ns)} (namespace sym))
    (symbol (name sym))
    sym))

(declare desugar)

(defn- template-part
  "One argument of a template's concat: (list x) is the element x, anything else
  is a ~@ splice, and the spliced expression is code in its own right."
  [part]
  (if (core-call? part 'list)
    (let [x (second part)]
      (if (and (seq? x) (= 'quote (first x)) (symbol? (second x)))
        (written-symbol (second x))
        (desugar x)))
    (desugar part)))

(defn- template-elements
  "The elements of (seq (concat ...)), or nil when form is not that shape."
  [form]
  (when (and (core-call? form 'seq) (core-call? (second form) 'concat))
    (map template-part (rest (second form)))))

(defn- pairs->map [xs]
  (if (even? (count xs)) (apply hash-map xs) (apply list xs)))

(defn- template-collection
  "A vector, map or set template: (apply vector (seq (concat ...))) and friends."
  [form]
  (when (and (core-call? form 'apply) (= 3 (count form)))
    (let [[_ ctor body] form
          elements (template-elements body)]
      (when elements
        (case ctor
          clojure.core/vector (vec elements)
          clojure.core/hash-map (pairs->map elements)
          clojure.core/hash-set (set elements)
          nil)))))

(defn desugar
  "Undo the reader's expansion of syntax-quote, so a macro's template is measured
  as written rather than as the seq/concat/list calls it reads into."
  [form]
  (cond
    (seq? form) (or (template-collection form)
                    (when-let [elements (template-elements form)] (apply list elements))
                    (apply list (map desugar form)))
    (vector? form) (mapv desugar form)
    (map? form) (into {} (map (fn [[k v]] [(desugar k) (desugar v)]) form))
    (set? form) (set (map desugar form))
    :else form))

;; --- the definitions of a file -------------------------------------------

(defn- split-arities
  "The arities of a function's tail, the forms after its name: an optional
  docstring and attr-map, then [params] body... or several ([params] body...)."
  [tail]
  (let [tail (if (string? (first tail)) (rest tail) tail)
        tail (if (map? (first tail)) (rest tail) tail)]
    (if (vector? (first tail))
      [{:params (first tail) :body (rest tail)}]
      (for [a tail :when (and (seq? a) (vector? (first a)))]
        {:params (first a) :body (rest a)}))))

(defn- param-count
  "How many parameters a params vector declares; `& rest` is one."
  [params]
  (count (remove #{'&} params)))

(defn- unit
  "A measurable definition: its name, kind and every arity's body."
  [uname kind form arities]
  {:name uname
   :kind kind
   :line (:line (meta form) 0)
   :column (:column (meta form) 0)
   :params (reduce max 0 (map (comp param-count :params) arities))
   :body (vec (mapcat :body arities))})

(defn- fn-form? [form]
  (and (seq? form) (contains? '#{fn fn*} (head-name form))))

(defn- fn-arities
  "The arities of an (fn name? ...) form."
  [form]
  (let [tail (rest form)]
    (split-arities (if (symbol? (first tail)) (rest tail) tail))))

(defn- method-units
  "The units of method forms (m [x] ...) or (m ([x] ...) ...), named owner/m."
  [owner forms]
  (for [m forms :when (and (seq? m) (symbol? (first m)))]
    (unit (str owner "/" (first m)) :method m (split-arities (rest m)))))

(defn- extend-protocol-units
  "extend-protocol alternates a type with the methods it gets."
  [forms]
  (mapcat (fn [[types methods]] (method-units (last types) methods))
          (partition 2 (partition-by seq? forms))))

(defn- definition-units
  "The units a top-level form defines; empty when it defines none."
  [form]
  (when (and (seq? form) (symbol? (first form)))
    (let [h (head-name form)
          target (second form)
          args (drop 2 form)]
      (case h
        (defn defn-) [(assoc (unit (str target) :function form (split-arities args))
                             :private (or (= 'defn- h) (boolean (:private (meta target)))))]
        defmacro [(unit (str target) :macro form (split-arities args))]
        (def defonce defmulti)
        (when-let [f (first (filter fn-form? args))]
          [(assoc (unit (str target) :function form (fn-arities f))
                  :private (boolean (:private (meta target))))])
        defmethod (let [tail (rest args)
                        tail (if (symbol? (first tail)) (rest tail) tail)]
                    [(unit (str target " " (pr-str (first args))) :method form
                           (split-arities tail))])
        (defrecord deftype) (method-units target (rest args))
        extend-type (method-units target args)
        extend-protocol (extend-protocol-units args)
        (when (= "deftest" (name (first form)))
          [(unit (str target) :test form [{:params [] :body args}])])))))

(defn- top-forms
  "The top-level forms, with top-level do blocks opened."
  [forms]
  (mapcat (fn [form]
            (if (and (seq? form) (= 'do (first form)))
              (top-forms (rest form))
              [form]))
          forms))

(defn functions
  "Every measurable definition of a file: [{:name :kind :line :params :body}]."
  [forms]
  (map #(update % :body desugar) (mapcat definition-units (top-forms forms))))

(defn- ns-form [forms]
  (first (filter #(and (seq? %) (= 'ns (first %))) forms)))

(defn namespace-name
  "The name a file's ns form declares, or nil."
  [forms]
  (let [n (ns-form forms)]
    (when (and n (symbol? (second n)))
      (second n))))

(defn imports
  "The namespaces a file requires or imports, from its ns form."
  [forms]
  (let [n (ns-form forms)]
    (if-not n
      []
      (vec (distinct
            (for [clause (rest n)
                  :when (and (seq? clause) (#{:require :use :import} (first clause)))
                  spec (rest clause)
                  :let [sym (cond (symbol? spec) spec
                                  (or (vector? spec) (seq? spec)) (first spec))]
                  :when sym]
              sym))))))

;; --- decision kinds (cyclomatic) -----------------------------------------

(defn- cond-decision-count
  "One per clause, but a trailing keyword or true test is the default."
  [args]
  (let [tests (take-nth 2 args)
        n (count tests)
        lst (last tests)]
    (if (and (pos? n) (or (keyword? lst) (true? lst)))
      (dec n)
      n)))

(defn- condp-decision-count
  "One per clause of (condp pred expr clause...); a clause is test result or
  test :>> f, and a lone trailing form is the default."
  [args]
  (loop [clauses (drop 2 args) n 0]
    (cond
      (empty? clauses) n
      (= :>> (second clauses)) (recur (drop 3 clauses) (inc n))
      (next clauses) (recur (drop 2 clauses) (inc n))
      :else n)))

(defn- case-decision-count [args]
  (quot (dec (count args)) 2))

(defn- comprehension-kinds
  "for/doseq: each binding is a loop, each :when or :while a branch."
  [args]
  (let [bindings (first args)]
    (if (and (vector? bindings) (seq bindings))
      (keep (fn [[k _]]
              (case k
                :let nil
                (:when :while) :if
                :loop))
            (partition 2 bindings))
      [:loop])))

(defn- own-decisions
  "The decisions form opens itself, leaving out those of its sub-forms."
  [form]
  (let [h (head-name form)
        args (rest form)]
    (cond
      (contains? if-forms h) [:if]
      (contains? '#{for doseq} h) (comprehension-kinds args)
      (contains? loop-forms h) [:loop]
      (contains? logical-forms h) (repeat (max 0 (dec (count args))) :logical)
      (= 'cond h) (repeat (cond-decision-count args) :if)
      (= 'condp h) (repeat (condp-decision-count args) :if)
      (= 'case h) (repeat (case-decision-count args) :if)
      (contains? cond-threading-forms h) (repeat (case-decision-count args) :if)
      (= 'catch h) [:catch]
      :else nil)))

(defn decision-kinds
  "The decision points of form, one keyword per branch, walked depth-first."
  [form]
  (vec (concat (when (seq? form) (own-decisions form))
               (mapcat decision-kinds (code-children form)))))

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
  (if (seq? form)
    (let [h (head-name form)
          args (rest form)]
      (cond
        (contains? two-way-forms h)
        [:If (flow-tree (first args))
             (flow-tree (second args))
             (if (> (count args) 2) [:Else (flow-tree (nth args 2))] [:Leaf])]

        (contains? if-forms h)
        [:If (flow-tree (first args)) (seq-tree (rest args)) [:Leaf]]

        (or (contains? loop-forms h)
            (contains? '#{cond condp case try catch} h)
            (contains? cond-threading-forms h))
        [:Nest (seq-tree (code-children form))]

        :else (seq-tree (code-children form))))
    (seq-tree (code-children form))))

;; --- Halstead tokens -----------------------------------------------------

(defn- token
  "A name for an atom: a symbol as written, any other literal printed, so the
  string \"a\", the symbol a and the keyword :a stay three different operands."
  [x]
  (if (symbol? x) (str x) (pr-str x)))

(defn- list-operator
  "The operator a list contributes: its head, unless that head is itself code."
  [form]
  (let [h (first form)]
    (cond
      (symbol? h) (str (head-name form))
      (or (coll? h) (empty? form)) nil
      :else (token h))))

(declare halstead*)

(defn- list-tokens [acc form]
  (let [h (head-name form)
        op (list-operator form)
        acc (if op (update acc :operators conj op) acc)]
    (case h
      comment acc
      quote (update acc :operands conj (token (second form)))
      case (let [acc (update acc :operands into (map token (:constants (case-parts (rest form)))))]
             (reduce halstead* acc (code-children form)))
      (reduce halstead* acc (code-children form)))))

(defn- halstead* [acc form]
  (cond
    (and (seq? form) (= 'comment (head-name form))) acc
    (seq? form) (list-tokens acc form)
    (coll? form) (reduce halstead* acc (code-children form))
    :else (update acc :operands conj (token form))))

(defn halstead-tokens
  "Halstead operators (the head of each list) and operands (literals and symbols)."
  [form]
  (halstead* {:operators [] :operands []} form))

;; --- logical LOC ---------------------------------------------------------

(defn form-count
  "The logical LOC of a scope: the number of list forms in it, nested included."
  [form]
  (cond
    (and (seq? form) (= 'comment (head-name form))) 0
    (seq? form) (inc (reduce + 0 (map form-count (code-children form))))
    :else (reduce + 0 (map form-count (code-children form)))))

;; --- the whole file ------------------------------------------------------

(defn- top-level-code
  "The top-level forms that define nothing measurable and are not the ns form."
  [forms]
  (map desugar
       (remove #(or (seq (definition-units %))
                    (and (seq? %) (= 'ns (first %))))
               (top-forms forms))))

(defn analyze-file
  "Parse source into the per-file model (no line-based LOC; see metrics.core)."
  [path source]
  (let [forms (read-forms source)
        top-code (top-level-code forms)]
    {:path path
     :language :clojure
     :namespace (namespace-name forms)
     :imports (imports forms)
     :functions
     (for [f (functions forms)
           :let [body (:body f)
                 tokens (reduce halstead* {:operators [] :operands []} body)]]
       (assoc (select-keys f [:name :kind :line :column :params :private])
              :lloc (reduce + 0 (map form-count body))
              :decision-kinds (mapcat decision-kinds body)
              :flow (seq-tree body)
              :operators (:operators tokens)
              :operands (:operands tokens)))
     :top-level-decision-kinds (mapcat decision-kinds top-code)
     :top-level-flow (seq-tree top-code)}))
