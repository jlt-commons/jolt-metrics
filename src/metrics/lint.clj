;; The rules, their messages and their default levels are adapted from clj-kondo
;; (https://github.com/clj-kondo/clj-kondo), Copyright (c) Michiel Borkent,
;; distributed under the Eclipse Public License 1.0: chiefly
;; clj-kondo.impl.linters, clj-kondo.impl.analyzer, clj-kondo.impl.docstring
;; and the :linters defaults in clj-kondo.impl.config.  clj-kondo works on
;; rewrite-clj nodes with resolved vars; this works on read forms, by head name.
(ns metrics.lint
  "Idiom and likely-bug findings over read Clojure forms: a redundant do or let,
  (if x x y), a loop without recur, a duplicate case constant, docstring style and
  the like.  Each finding is {:rule :line :column :message}; with-levels gives
  each its level from a project's :linters config, and drops those turned :off.

  Quoted forms and comment blocks are data, not code, and are not linted.  Heads
  are matched by name, with a clojure.core qualifier removed, so a local shadowing
  a core name can make a false finding.  Syntax-quoted templates are walked as the
  reader expands them, which leaves only their unquoted code to lint."
  (:require [clojure.string :as str]))

;; --- levels --------------------------------------------------------------

(def default-levels
  "Each rule's level unless a project's :linters says otherwise: clj-kondo's
  defaults."
  {:redundant-do :warning :redundant-let :warning :redundant-let-binding :off
   :cond-else :warning :constant-condition :warning
   :missing-else-branch :warning :if-nil-return :off :if-x-x-y :off
   :missing-body-in-when :warning :missing-clause-in-try :warning
   :single-operand-comparison :warning :single-logical-operand :warning
   :redundant-nested-call :info :redundant-str-call :info
   :redundant-call :off :redundant-fn-wrapper :off
   :equals-true :off :equals-false :off :equals-nil :off
   :plus-one :off :minus-one :off :not-empty? :warning :single-key-in :off
   :inline-def :warning :loop-without-recur :warning :case-duplicate-test :error
   :earmuffed-var-not-dynamic :warning :dynamic-var-not-earmuffed :off :def-fn :off
   :missing-docstring :off :docstring-blank :warning :docstring-no-summary :off
   :docstring-leading-trailing-whitespace :off :misplaced-docstring :warning
   :refer-all :warning :use :warning :unsorted-required-namespaces :off
   :redundant-ignore :info
   ;; ours, not clj-kondo's: work that grows faster than it needs to
   :str-accumulation :info :concat-accumulation :info
   :contains-on-sequential :warning :linear-membership :off
   :copying-pop :info :last-on-vector :info :lazy-into :off})

(def levels
  "What a rule's :level can be."
  #{:off :info :warning :error})

(defn level-of
  "A rule's level under a project's :linters config."
  [linters rule]
  (get-in linters [rule :level] (get default-levels rule :warning)))

(defn with-levels
  "findings, each with its level, leaving out those whose rule is :off."
  [findings linters]
  (vec (keep #(let [l (level-of linters (:rule %))]
                (when (not= :off l) (assoc % :level l)))
             findings)))

(defn- linter-problem [[rule setting]]
  (cond
    (not (contains? default-levels rule)) (str "unknown linter: " (pr-str rule))
    (not (and (map? setting) (contains? levels (:level setting))))
    (str (pr-str rule) " takes {:level :off|:info|:warning|:error}")
    :else nil))

(defn config-problem
  "What is wrong with a :linters config, or nil."
  [linters]
  (cond
    (nil? linters) nil
    (not (map? linters)) ":linters should be a map of rule to {:level ...}"
    :else (some linter-problem linters)))

;; --- forms -------------------------------------------------------------

(defn- head
  "A list's head symbol, with a clojure.core qualifier removed; nil when it has none."
  [form]
  (when (and (seq? form) (symbol? (first form)))
    (let [h (first form)]
      (if (= "clojure.core" (namespace h)) (symbol (name h)) h))))

(defn- hit
  "One finding of rule, at the form being linted."
  [rule message]
  [{:rule rule :message message}])

(defn- truthy-constant? [x]
  (or (keyword? x) (string? x) (number? x) (char? x) (true? x)))

;; --- bodies ------------------------------------------------------------

(def ^:private body-start
  "Where the implicit do of each form begins."
  '{do 1 try 1 future 1 fn 2 defn 2 defn- 2 let 2 loop 2 binding 2 with-open 2
    doseq 2 dotimes 2 letfn 2 when 2 when-not 2 when-let 2 when-some 2 when-first 2
    catch 3})

(defn- redundant-do
  "A do with one form or none, or one in an implicit do.  #(do [%1 %2]) is how a
  fn literal returns a vector, so it is left alone."
  [form {:keys [parent index]}]
  (let [start (get body-start (head parent))]
    (when (and (not= 'fn* (head parent))
               (or (< (count form) 3) (and start (>= index start))))
      (hit :redundant-do "redundant do"))))

(defn- redundant-let [form _]
  (let [[_ bindings & body] form]
    (when (or (and (vector? bindings) (empty? bindings))
              (and (= 1 (count body)) (= 'let (head (first body)))))
      (hit :redundant-let "Redundant let expression."))))

(defn- redundant-let-binding [form _]
  (let [bindings (second form)]
    (when (vector? bindings)
      (for [[b v] (partition 2 bindings)
            :when (and (simple-symbol? b) (= b v) (nil? (meta b)))]
        {:rule :redundant-let-binding :message (str "Redundant let binding: " b)}))))

(defn- recurs?
  "Does form recur to the loop it is in?  A nested fn or loop has its own target."
  [form]
  (cond
    (= 'recur (head form)) true
    (contains? '#{fn fn* loop quote} (head form)) false
    (coll? form) (boolean (some recurs? (if (seq? form) (rest form) (seq form))))
    :else false))

(defn- loop-without-recur [form _]
  (when-not (some recurs? (drop 2 form))
    (hit :loop-without-recur "Loop without recur.")))

(defn- missing-body-in-when [form _]
  (when (< (count form) 3)
    (hit :missing-body-in-when "Missing body in when")))

(defn- missing-clause-in-try [form _]
  (when-not (some #(contains? '#{catch finally} (head %)) (rest form))
    (hit :missing-clause-in-try "Missing catch or finally in try")))

;; --- conditionals ------------------------------------------------------

(defn- cond-rules
  "A truthy constant test that isn't :else, and the clauses after one."
  [form _]
  (let [tests (vec (take-nth 2 (rest form)))]
    (mapcat (fn [i t]
              (when (truthy-constant? t)
                (concat
                 (when (not= :else t)
                   (hit :cond-else "use :else as the catch-all test expression in cond"))
                 (when (< (inc i) (count tests))
                   (hit :constant-condition "Unreachable code")))))
            (range)
            tests)))

(def ^:private when-for-nil-else '{if when if-let when-let if-some when-some if-not when-not})
(def ^:private when-for-nil-then '{if when-not if-not when})

(defn- nil-return [form]
  (let [[_ _ then else] form
        h (head form)
        preferred (when (= 4 (count form))
                    (cond
                      (nil? else) (when-for-nil-else h)
                      (nil? then) (when-for-nil-then h)
                      :else nil))]
    (when preferred
      (hit :if-nil-return (str "For nil return, prefer " preferred ".")))))

(defn- if-x-x-y [form]
  (let [[_ c then] form]
    (when (and (= 'if (head form)) (= 4 (count form)) (symbol? c) (= c then))
      (hit :if-x-x-y "If condition and then branch are the same; use (or x y)"))))

(defn- if-rules [form _]
  (concat
   (when (= 3 (count form)) (hit :missing-else-branch "Missing else branch."))
   (nil-return form)
   (if-x-x-y form)))

(defn- case-constants [form]
  (mapcat #(if (seq? %) % [%]) (map first (partition 2 (drop 2 form)))))

(defn- case-duplicate-test [form _]
  (for [[c n] (frequencies (case-constants form))
        :when (> n 1)]
    {:rule :case-duplicate-test :message (str "Duplicate case test constant: " (pr-str c))}))

;; --- calls -------------------------------------------------------------

(defn- single-operand-comparison [form _]
  (when (= 2 (count form))
    (let [h (head form)]
      (hit :single-operand-comparison
           (str "Single operand use of clojure.core/" h " is always " (not= 'not= h))))))

(defn- single-logical-operand [form _]
  (when (= 2 (count form))
    (hit :single-logical-operand
         (str "Single arg use of " (head form) " always returns the arg itself"))))

(defn- redundant-nested-call [form {:keys [parent index]}]
  (when (and (= (head form) (head parent)) (pos? index))
    (hit :redundant-nested-call (str "Redundant nested call: " (head form)))))

(defn- redundant-str-call [form _]
  (when (and (= 2 (count form)) (string? (second form)))
    (hit :redundant-str-call "Single argument to str already is a string")))

(defn- redundant-call [form _]
  (when (= 2 (count form))
    (hit :redundant-call
         (str "Single arg use of clojure.core/" (head form) " always returns the arg itself"))))

(defn- passes-through?
  "Can sym be passed as a value in place of a fn that calls it?  Not interop, a
  special form or a core macro."
  [sym]
  (let [n (name sym)
        q (namespace sym)]
    (not (or (str/starts-with? n ".") (str/ends-with? n ".")
             (and q (Character/isUpperCase (first q)))
             (special-symbol? sym)
             (some-> (try (ns-resolve (the-ns 'clojure.core) sym) (catch Exception _ nil))
                     meta :macro)))))

(defn- wrapper-call? [params call]
  (and (seq? call) (symbol? (first call))
       (= params (vec (rest call)))
       (not-any? #{(first call)} params)
       (passes-through? (first call))))

(defn- redundant-fn-wrapper [form _]
  (let [named? (symbol? (second form))
        [params body & more] (drop (if named? 2 1) form)]
    (when (and (vector? params) (every? simple-symbol? params) (not-any? #{'&} params)
               (empty? more) (wrapper-call? params body))
      (hit :redundant-fn-wrapper "Redundant fn wrapper"))))

(defn- equals-rules [form _]
  (when (= 3 (count form))
    (let [args (set (rest form))]
      (concat
       (when (contains? args true) (hit :equals-true "Prefer (true? x) over (= true x)"))
       (when (contains? args false) (hit :equals-false "Prefer (false? x) over (= false x)"))
       (when (contains? args nil) (hit :equals-nil "Prefer (nil? x) over (= nil x)"))))))

(defn- plus-one [[_ lhs rhs :as form] _]
  (when (and (= 3 (count form)) (or (= 1 lhs) (= 1 rhs)))
    (hit :plus-one "Prefer (inc x) over (+ 1 x)")))

(defn- minus-one [[_ _ rhs :as form] _]
  (when (and (= 3 (count form)) (= 1 rhs))
    (hit :minus-one "Prefer (dec x) over (- x 1)")))

(defn- not-empty? [form _]
  (when (and (= 2 (count form)) (= 'empty? (head (second form))))
    (hit :not-empty? "Use (seq x) instead of (not (empty? x)) when x is a seq")))

(defn- single-key-in [form _]
  (let [ks (nth form 2 nil)]
    (when (and (vector? ks) (= 1 (count ks)))
      (hit :single-key-in (str (head form) " with single key")))))

;; --- definitions -------------------------------------------------------

(def ^:private def-heads
  '#{def defn defn- defmacro defonce defmulti defmethod deftype defrecord defprotocol
     deftest})

(defn- inline-def [_ {:keys [in-def]}]
  (when in-def (hit :inline-def "inline def")))

(defn- earmuffs [form _]
  (let [n (second form)]
    (when (symbol? n)
      (let [earmuffed? (re-matches #"\*+[^\*]+\*+" (name n))
            dynamic? (:dynamic (meta n))]
        (cond
          (and dynamic? (not earmuffed?))
          (hit :dynamic-var-not-earmuffed (str "Var is declared dynamic but name is not earmuffed: " n))
          (and earmuffed? (not dynamic?))
          (hit :earmuffed-var-not-dynamic (str "Var has earmuffed name but is not declared dynamic: " n))
          :else nil)))))

(defn- def-fn [form _]
  (when (and (>= (count form) 3) (contains? '#{fn fn*} (head (last form))))
    (hit :def-fn "Use defn instead of def + fn")))

;; --- docstrings --------------------------------------------------------

(defn- def-parts
  "A definition's name, its docstring or nil, and the forms after them.  A
  string with nothing after it is a value, not a docstring."
  [form]
  (let [[_ nm & more] form
        doc (when (and (string? (first more)) (next more)) (first more))]
    {:name nm :doc doc :after (if doc (rest more) more)}))

(defn- summary? [doc]
  (let [line (str/trim (or (first (str/split-lines doc)) ""))]
    (boolean (and (seq line)
                  (Character/isUpperCase (first line))
                  (re-matches #"[.!?]" (str (last line)))))))

(defn- docstring-style [form _]
  (when-let [doc (:doc (def-parts form))]
    (concat
     (when (str/blank? doc) (hit :docstring-blank "Docstring should not be blank."))
     (when-not (or (str/blank? doc) (summary? doc))
       (hit :docstring-no-summary
            "First line of the docstring should be a capitalized sentence ending with punctuation."))
     (when (not= doc (str/trim doc))
       (hit :docstring-leading-trailing-whitespace
            "Docstring should not have leading or trailing whitespace.")))))

(defn- private? [form]
  (or (= 'defn- (head form)) (:private (meta (second form)))))

(defn- missing-docstring [form _]
  (let [{:keys [doc after]} (def-parts form)]
    (when-not (or doc (private? form) (:doc (meta (second form)))
                  (and (map? (first after)) (:doc (first after))))
      (hit :missing-docstring "Missing docstring."))))

(defn- misplaced-docstring [form _]
  (let [{:keys [doc after]} (def-parts form)
        [params s & body] (if (map? (first after)) (rest after) after)]
    (when (and (nil? doc) (vector? params) (string? s) (seq body))
      (hit :misplaced-docstring "Misplaced docstring."))))

;; --- namespaces --------------------------------------------------------

(defn- ns-clauses [form k]
  (filter #(and (seq? %) (= k (first %))) (drop 2 form)))

(defn- refers-all? [libspec]
  (and (vector? libspec)
       (even? (count (rest libspec)))
       (= :all (:refer (apply hash-map (rest libspec))))))

(defn- libspec-name [libspec]
  (cond
    (vector? libspec) (first libspec)
    (symbol? libspec) libspec
    :else nil))

(defn- first-unsorted [names]
  (some (fn [[a b]] (when (pos? (compare (str/lower-case (str a)) (str/lower-case (str b)))) b))
        (partition 2 1 names)))

(defn- ns-rules [form _]
  (let [libspecs (mapcat rest (ns-clauses form :require))]
    (concat
     (when (some refers-all? libspecs) (hit :refer-all "use alias or :refer"))
     (when (seq (ns-clauses form :use)) (hit :use "use :require with alias or :refer"))
     (when-let [n (first-unsorted (keep libspec-name libspecs))]
       (hit :unsorted-required-namespaces (str "Unsorted namespace: " n))))))

(defn- top-level-use [_ _]
  (hit :use "use :require with alias or :refer"))

;; --- performance -------------------------------------------------------
;; Not clj-kondo's: shapes whose cost grows faster than the work needs, after
;; the Agentic Algorithm Engineering knowledge base (string building, membership
;; tests, queues, lazy pipelines).  Each looks at a call and its argument, so a
;; threading form's steps are checked again once unthreaded (unthreaded-rules).

(defn- fn-shape
  "The params and last body form of a single-arity fn or fn literal, or nil."
  [form]
  (when (contains? '#{fn fn*} (head form))
    (let [more (if (symbol? (second form)) (drop 2 form) (rest form))
          [params & body] more]
      (when (and (vector? params) (seq body))
        [params (last body)]))))

(defn- grows?
  "Does f, the reducing fn of a reduce, build its result by calling grow on the
  accumulator: grow itself, or a fn whose result is (grow acc ...)?"
  [grow f]
  (or (= grow f)
      (let [[params body] (fn-shape f)]
        (boolean (and (seq params) (= grow (head body)) (= (first params) (second body)))))))

(def ^:private accumulations
  {'str [:str-accumulation "Building a string piece by piece copies it every step; use clojure.string/join, apply str or a StringBuilder"]
   'concat [:concat-accumulation "concat onto an accumulator nests lazy seqs and can overflow the stack; use into or a vector"]})

(defn- reduce-accumulation [form _]
  (let [f (second form)]
    (for [[grow [rule message]] accumulations
          :when (grows? grow f)]
      {:rule rule :message message})))

(defn- recur-forms
  "The recur forms of a loop body, leaving out nested fns and loops."
  [form]
  (cond
    (= 'recur (head form)) [form]
    (contains? '#{fn fn* loop quote} (head form)) []
    (coll? form) (mapcat recur-forms (if (seq? form) (rest form) (seq form)))
    :else []))

(defn- loop-accumulation [form _]
  (let [bindings (second form)
        names (when (vector? bindings) (take-nth 2 bindings))]
    (distinct
     (for [r (mapcat recur-forms (drop 2 form))
           [nm arg] (map vector names (rest r))
           :let [[rule message] (get accumulations (head arg))]
           :when (and rule (symbol? nm) (= nm (second arg)))]
       {:rule rule :message message}))))

(def ^:private makes-vector '#{vec vector mapv filterv subvec})

(defn- vector-call?
  "Does form make a vector: a literal, a vector-making call or (into [] ...)?"
  [form]
  (or (vector? form)
      (contains? makes-vector (head form))
      (and (= 'into (head form)) (= [] (second form)))))

(defn- contains-on-sequential [form _]
  (let [coll (second form)]
    (when (and (= 3 (count form))
               (or (vector-call? coll)
                   (= 'list (head coll))
                   (and (= 'quote (head coll)) (seq? (second coll)))))
      (hit :contains-on-sequential
           "contains? on a vector or list checks indices, not values; use a set or some"))))

(defn- equals-param?
  "Is form (= p y) or (= y p), y not p itself?"
  [p form]
  (and (= '= (head form)) (= 3 (count form))
       (let [[_ a b] form]
         (or (and (= p a) (not= p b)) (and (= p b) (not= p a))))))

(defn- linear-membership [form _]
  (let [[params body] (fn-shape (second form))]
    (when (and (= 3 (count form)) (= 1 (count params)) (equals-param? (first params) body))
      (hit :linear-membership "Membership by linear search; keep the values in a set"))))

(defn- drops-head?
  "Is form (rest nm), (next nm) or (drop 1 nm)?"
  [nm form]
  (or (and (contains? '#{rest next} (head form)) (= [nm] (rest form)))
      (and (= 'drop (head form)) (= [1 nm] (rest form)))))

(defn- unthread
  "The forms of a -> or ->> pipeline's steps, each with the steps before it
  nested inside, as the macro expands them."
  [form]
  (let [last? (= '->> (head form))
        step (fn [acc s]
               (cond
                 (not (seq? s)) (list s acc)
                 last? (concat s [acc])
                 :else (apply list (first s) acc (rest s))))]
    (rest (reductions step (second form) (drop 2 form)))))

(defn- unthreaded
  "form with a -> or ->> pipeline expanded, as the macro would."
  [form]
  (if (contains? '#{-> ->>} (head form)) (or (last (unthread form)) form) form))

(defn- copies-without-head?
  "Is form a vector rebuilt from nm without its first item, or does it build
  on one: (vec (rest nm)), (into [] (rest nm)), (conj (vec (rest nm)) x)?"
  [nm form]
  (let [form (unthreaded form)]
    (cond
      (and (= 'vec (head form)) (= 2 (count form))) (drops-head? nm (unthreaded (second form)))
      (and (= 'into (head form)) (= 3 (count form)) (= [] (second form)))
      (drops-head? nm (unthreaded (last form)))
      (contains? '#{conj into} (head form)) (copies-without-head? nm (second form))
      :else false)))

(defn- copying-pop
  "A loop that pops a vector by copying it: a recur that rebinds a loop name to
  the vector rebuilt without its head."
  [form _]
  (let [bindings (second form)
        names (when (vector? bindings) (take-nth 2 bindings))]
    (when (some (fn [r] (some (fn [[nm arg]] (and (symbol? nm) (copies-without-head? nm arg)))
                              (map vector names (rest r))))
                (mapcat recur-forms (drop 2 form)))
      (hit :copying-pop
           "Rebuilding a vector to drop its first item copies all of it, every time round the loop; use subvec, or clojure.lang.PersistentQueue for a FIFO"))))

(defn- last-on-vector [form _]
  (when (and (= 2 (count form)) (vector-call? (second form)))
    (hit :last-on-vector "last walks a vector from the front; use peek")))

(def ^:private lazy-steps '#{map filter remove keep mapcat take-while drop-while map-indexed})

(defn- lazy-into [form _]
  (let [arg (nth form 2 nil)]
    (when (and (= 3 (count form)) (contains? lazy-steps (head arg)) (>= (count arg) 3))
      (hit :lazy-into
           (str "(into to (" (head arg) " ...)) makes a lazy seq only to pour it out; use the transducer: (into to (" (head arg) " f) coll)")))))

(def ^:private pair-rules
  "The rules that judge a call by its argument, which a threading form hides."
  {'contains? [contains-on-sequential] 'into [lazy-into] 'last [last-on-vector]})

(defn- unthreaded-rules [form ctx]
  (for [step (unthread form)
        rule (get pair-rules (head step))
        f (rule step ctx)]
    f))

;; --- the table ---------------------------------------------------------

(defn- for-heads [heads rule]
  (zipmap heads (repeat [rule])))

(def ^:private rules-by-head
  (merge-with
   into
   {'do [redundant-do] 'let [redundant-let redundant-let-binding]
    'loop [loop-without-recur loop-accumulation copying-pop] 'try [missing-clause-in-try] 'cond [cond-rules]
    'case [case-duplicate-test] 'str [redundant-str-call] '= [equals-rules]
    '+ [plus-one] '- [minus-one] 'not [not-empty?] 'ns [ns-rules] 'use [top-level-use]
    'def [earmuffs def-fn] 'defonce [earmuffs]
    'reduce [reduce-accumulation] 'some [linear-membership]
    '-> [unthreaded-rules] '->> [unthreaded-rules]}
   pair-rules
   (for-heads '[if if-not if-let if-some] if-rules)
   (for-heads '[when when-not when-let when-some when-first] missing-body-in-when)
   (for-heads '[= > < >= <= == not=] single-operand-comparison)
   (for-heads '[and or] single-logical-operand)
   (for-heads '[* *' + +' and or lazy-cat max merge min str] redundant-nested-call)
   (for-heads '[-> ->> cond-> cond->> some-> some->> partial comp merge] redundant-call)
   (for-heads '[fn fn*] redundant-fn-wrapper)
   (for-heads '[get-in assoc-in update-in] single-key-in)
   (for-heads def-heads inline-def)
   (for-heads '[defn defn- defmacro defmulti def] docstring-style)
   (for-heads '[defn defmacro defmulti] missing-docstring)
   (for-heads '[defn defn-] misplaced-docstring)))

;; --- the walk ----------------------------------------------------------

(defn- indexed-children
  "[index sub-form] for each sub-form of form that is code."
  [form]
  (cond
    (map? form) (map vector (range) (mapcat identity form))
    (or (vector? form) (set? form)) (map vector (range) form)
    (not (seq? form)) nil
    (contains? '#{quote comment} (head form)) nil
    (= 'case (head form)) (remove (fn [[i _]] (and (>= i 2) (even? i) (< (inc i) (count form))))
                                  (map vector (range) form))
    :else (map vector (range) form)))

(defn- position [form {:keys [line column]}]
  (let [m (when (seq? form) (meta form))]
    {:line (or (:line m) line) :column (or (:column m) column)}))

(def ^:private thread-first '#{-> some-> cond-> doto})
(def ^:private thread-last '#{->> some->> cond->>})

(defn- step?
  "Is the sub-form at index of a threading form one of its steps?  A cond->
  takes a test before each step."
  [threading index]
  (if (contains? '#{cond-> cond->>} threading)
    (and (>= index 3) (odd? index))
    (>= index 2)))

(defn- as-called
  "form as it is called: a threading step gets the threaded value as an argument,
  first or last, so its arity is what the call sees."
  [form {:keys [parent index]}]
  (let [h (head parent)
        threaded 'metrics.lint/threaded]
    (cond
      (not (and (seq? form) (step? h index))) form
      (contains? thread-first h) (apply list (first form) threaded (rest form))
      (contains? thread-last h) (concat form [threaded])
      :else form)))

(defn- list-findings [form ctx]
  (let [called (as-called form ctx)]
    (for [rule (get rules-by-head (head form))
          f (rule called ctx)]
      (merge f (select-keys ctx [:line :column])))))

(defn- walk [form ctx]
  (let [here (merge ctx (position form ctx))
        inner (assoc here
                     :parent form
                     :in-def (or (:in-def ctx) (contains? def-heads (head form))))]
    (concat
     (when (seq? form) (list-findings form here))
     (mapcat (fn [[i c]] (walk c (assoc inner :index i))) (indexed-children form)))))

(defn findings
  "The idiom findings in read forms, in source order."
  [forms]
  (vec (sort-by (juxt :line :column)
                (mapcat #(walk % {:line 1 :column 1 :index 0 :in-def false}) forms))))
