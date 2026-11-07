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

  Cognitive complexity: if/when/condp/case/loops/catch add 1 plus nesting depth; a
  cond is an if/else-if chain and a cond-> a series of ifs; an else-if and a logical
  operator run add a flat 1; try adds nothing; a closure (fn, letfn, reify, proxy)
  adds nothing but nests what it holds.")

;; --- reading -------------------------------------------------------------

(def ^:private scratch-ns
  "The namespace source is read in, so that nothing resolves against the caller's."
  'metrics.read-scratch)

(def ^:private alias-placeholder
  "What an auto-resolved alias a resolves to is this followed by a."
  "metrics.read-alias.")

(defn- reading-ns
  "The scratch namespace, with every alias source auto-resolves (`::a/kw`, `#::a{}`) bound
  to a placeholder.  Metrics only compare keywords, so the real target does not
  matter, and scanning the text also covers aliases made by a top-level
  `require` or `alias`, not just the ns form."
  [source]
  (let [n (create-ns scratch-ns)]
    (binding [*ns* n]
      (refer-clojure)
      (doseq [a (distinct (map second (re-seq #"::([^\s/\"()\[\]{}]+)[/{]" source)))]
        (let [target (symbol (str alias-placeholder a))]
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
(def when-forms '#{when when-not when-let when-some when-first})
(def if-forms (into two-way-forms when-forms))
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

(defn- list-children
  "The sub-forms of a list that are code; its head too, when that is computed."
  [form]
  (let [h (head-name form)
        args (rest form)]
    (cond
      (contains? '#{quote comment} h) nil
      (= 'case h) (let [{:keys [expr results]} (case-parts args)] (cons expr results))
      (= 'catch h) (drop 2 args)
      h args
      (coll? (first form)) form
      :else args)))

(defn- code-children
  "The sub-forms of form that are code."
  [form]
  (cond
    (map? form) (mapcat identity form)
    (or (vector? form) (set? form)) (seq form)
    (seq? form) (list-children form)
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
   :form form
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

(defn- private-name? [target]
  (boolean (:private (meta target))))

;; Each definer takes a definition form, the name it defines and the forms after
;; that name, and returns the units it defines.

(defn- defn-units [form target args]
  [(assoc (unit (str target) :function form (split-arities args))
          :private (or (= 'defn- (head-name form)) (private-name? target)))])

(defn- defmacro-units [form target args]
  [(unit (str target) :macro form (split-arities args))])

(defn- def-units
  "A def, defonce or defmulti is a unit only when its value is an fn."
  [form target args]
  (when-let [f (first (filter fn-form? args))]
    [(assoc (unit (str target) :function form (fn-arities f))
            :private (private-name? target))]))

(defn- defmethod-units
  "(defmethod m dispatch fn-name? ...), named for the method and its dispatch value."
  [form target args]
  (let [tail (rest args)
        tail (if (symbol? (first tail)) (rest tail) tail)]
    [(unit (str target " " (pr-str (first args))) :method form (split-arities tail))]))

(defn- deftype-units
  "defrecord or deftype: the methods after the fields."
  [_ target args]
  (method-units target (rest args)))

(defn- extend-type-units [_ target args]
  (method-units target args))

(defn- extend-protocol-definer [_ _ args]
  (extend-protocol-units args))

(defn- deftest-units [form target args]
  [(unit (str target) :test form [{:params [] :body args}])])

(def ^:private definers
  {'defn defn-units
   'defn- defn-units
   'defmacro defmacro-units
   'def def-units
   'defonce def-units
   'defmulti def-units
   'defmethod defmethod-units
   'defrecord deftype-units
   'deftype deftype-units
   'extend-type extend-type-units
   'extend-protocol extend-protocol-definer})

(defn- definer-of
  "The definer for a form, or nil.  deftest counts under any alias, since
  clojure.test is usually required as one."
  [form]
  (or (get definers (head-name form))
      (when (= "deftest" (name (first form))) deftest-units)))

(defn- definition-units
  "The units a top-level form defines; empty when it defines none."
  [form]
  (when (and (seq? form) (symbol? (first form)))
    (when-let [definer (definer-of form)]
      (definer form (second form) (drop 2 form)))))

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

(defn- expand-libspec
  "The libs a libspec names, as {:lib sym :as alias :refer [...]}.  A prefix list
  is [prefix & more] whose first more is not an option keyword."
  [prefix spec]
  (let [qualified #(if prefix (symbol (str prefix "." %)) %)]
    (cond
      (symbol? spec) [{:lib (qualified spec)}]
      (and (sequential? spec) (symbol? (first spec)))
      (let [[lib & more] spec]
        (if (and (seq more) (not (keyword? (first more))))
          (mapcat #(expand-libspec (qualified lib) %) more)
          [(assoc (when (even? (count more)) (apply hash-map more))
                  :lib (qualified lib))]))
      :else nil)))

(defn- import-package
  "The package an :import spec names: (pkg Class...) or the qualified pkg.Class."
  [spec]
  (cond (symbol? spec) spec
        (sequential? spec) (first spec)
        :else nil))

(defn- unquoted [form]
  (if (and (seq? form) (= 'quote (first form))) (second form) form))

(defn- top-level-libspecs
  "The libs of top-level (require ...) and (use ...) calls, the way a script
  loads them; a spec computed at runtime is not knowable from here."
  [forms]
  (for [form (top-forms forms)
        :when (and (seq? form) (contains? '#{require use} (first form)))
        spec (rest form)
        :when (and (seq? spec) (= 'quote (first spec)))
        lib (expand-libspec nil (unquoted spec))]
    lib))

(defn libspecs
  "Everything a file requires, from its ns form and top-level require or use calls."
  [forms]
  (let [n (ns-form forms)]
    (concat
     (for [clause (rest n)
           :when (and (seq? clause) (#{:require :require-macros :use :use-macros} (first clause)))
           spec (rest clause)
           lib (expand-libspec nil spec)]
       lib)
     (top-level-libspecs forms))))

(defn imports
  "The namespaces a file depends on, in source order, from its libspecs and any
  :import packages."
  [forms]
  (let [n (ns-form forms)
        packages (for [clause (rest n)
                       :when (and (seq? clause) (= :import (first clause)))
                       spec (rest clause)
                       :let [p (import-package spec)]
                       :when p]
                   p)]
    (vec (distinct (concat (map :lib (libspecs forms)) packages)))))

;; --- decision kinds (cyclomatic) -----------------------------------------

(defn- default-test?
  "Is this cond test always true, making its clause the default?"
  [test-form]
  (or (keyword? test-form) (true? test-form)))

(defn- cond-decision-count
  "One per clause, but a trailing keyword or true test is the default."
  [args]
  (let [tests (take-nth 2 args)
        n (count tests)]
    (if (and (pos? n) (default-test? (last tests)))
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

(defn- two-way-tree [form]
  (let [args (rest form)]
    [:If (flow-tree (first args))
         (flow-tree (second args))
         (if (> (count args) 2) [:Else (flow-tree (nth args 2))] [:Leaf])]))

(defn- when-tree
  "A when has no else: everything after the test is its body."
  [form]
  (let [args (rest form)]
    [:If (flow-tree (first args)) (seq-tree (rest args)) [:Leaf]]))

(defn- nest-tree [form]
  [:Nest (seq-tree (code-children form))])

(defn- logical-tree
  "One argument has no operator to read, so it adds nothing."
  [form]
  (let [args (rest form)]
    (if (next args)
      [:Flat (seq-tree args)]
      (seq-tree args))))

(defn- clause-chain
  "cond clauses as the if/else-if chain they read as; a trailing default is an else."
  [[test-form result & more]]
  (let [else-part (cond
                    (empty? more) [:Leaf]
                    (and (= 2 (count more)) (default-test? (first more)))
                    [:Else (flow-tree (second more))]
                    :else [:Else (clause-chain more)])]
    [:If (flow-tree test-form) (flow-tree result) else-part]))

(defn- cond-tree
  "An else-if chain, not a switch: each test is its own condition to read."
  [form]
  (if (seq (rest form))
    (clause-chain (rest form))
    [:Leaf]))

(defn- threading-tree
  "(cond-> x t1 e1 t2 e2) is the value, then an independent if per clause."
  [form]
  (let [[value & clauses] (rest form)
        ifs (map (fn [[t e]] [:If (flow-tree t) (flow-tree e) [:Leaf]])
                 (partition 2 clauses))]
    (cons-tree (cons (flow-tree value) ifs))))

(defn- closure-tree [form]
  [:Deep (seq-tree (rest form))])

(defn- letfn-tree [form]
  (let [args (rest form)]
    [:Seq [:Deep (flow-tree (first args))] (seq-tree (rest args))]))

(def ^:private tree-builders
  (merge (zipmap two-way-forms (repeat two-way-tree))
         (zipmap when-forms (repeat when-tree))
         (zipmap loop-forms (repeat nest-tree))
         (zipmap cond-threading-forms (repeat threading-tree))
         (zipmap '[condp case catch] (repeat nest-tree))
         {'cond cond-tree}
         (zipmap logical-forms (repeat logical-tree))
         (zipmap '[fn fn* reify proxy] (repeat closure-tree))
         {'letfn letfn-tree}))

(defn flow-tree
  "The cognitive tree of form:
  [:Leaf] | [:Seq h t] | [:If t th e] | [:Else x] | [:Nest b] | [:Flat b] | [:Deep b]."
  [form]
  (if-let [build (when (seq? form) (get tree-builders (head-name form)))]
    (build form)
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

;; --- what a file mentions -------------------------------------------------

(defn- atoms [form]
  (remove coll? (tree-seq coll? seq form)))

(defn- symbol-counts
  "How many times each symbol name occurs in forms, whatever its namespace."
  [forms]
  (frequencies (map name (filter symbol? (mapcat atoms forms)))))

(defn- alias-qualifier
  "The alias a placeholder namespace stands for, or nil if it is not one."
  [ns-name]
  (when (and ns-name (.startsWith ns-name alias-placeholder))
    (subs ns-name (count alias-placeholder))))

(defn- qualifiers
  "The namespaces and aliases forms refer through: symbol qualifiers as written,
  and auto-resolved aliases, which read to a placeholder.  A keyword with a plain
  :x/y namespace has nothing to do with an alias x."
  [forms]
  (set
   (keep (fn [a]
           (cond
             (symbol? a) (or (alias-qualifier (namespace a)) (namespace a))
             (keyword? a) (alias-qualifier (namespace a))
             :else nil))
         (mapcat atoms forms))))

;; --- the whole file ------------------------------------------------------

(defn- top-level-code
  "The top-level forms that define nothing measurable and are not the ns form."
  [forms]
  (map desugar
       (remove #(or (seq (definition-units %))
                    (and (seq? %) (= 'ns (first %))))
               (top-forms forms))))

(defn- function-model
  "What the metrics need of one definition."
  [f]
  (let [body (:body f)
        tokens (reduce halstead* {:operators [] :operands []} body)]
    (assoc (select-keys f [:name :kind :line :column :params :private])
           :self-refs (get (symbol-counts [(:form f)]) (:name f) 0)
           :lloc (reduce + 0 (map form-count body))
           :decision-kinds (mapcat decision-kinds body)
           :flow (seq-tree body)
           :operators (:operators tokens)
           :operands (:operands tokens))))

(defn analyze-file
  "Parse source into the per-file model (no line-based LOC; see metrics.core)."
  [path source]
  (let [forms (read-forms source)
        top-code (top-level-code forms)
        code (remove #(and (seq? %) (= 'ns (first %))) forms)]
    {:path path
     :language :clojure
     :namespace (namespace-name forms)
     :imports (imports forms)
     :libspecs (vec (libspecs forms))
     :symbols (symbol-counts code)
     :qualifiers (qualifiers code)
     :functions (map function-model (functions forms))
     :top-level-decision-kinds (mapcat decision-kinds top-code)
     :top-level-flow (seq-tree top-code)}))
