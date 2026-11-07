(ns metrics.scheme.normalize
  "Rewrite Scheme forms into the Clojure shapes metrics.reader's walker understands,
  so Scheme gets the same metrics from the same code rather than a second copy of
  them.  Scheme's meaning is decided here and nowhere else:

    (define (f x) ...)          (defn f [x] ...)
    (lambda (x) ...)            (fn [x] ...)
    an inner (define (g x) ...) (letfn [(g [x] ...)])
    (letrec ([g (lambda ...)]   (letfn [(g [...] ...)]
             [v e]) ...)          (let [v e] ...))
    (let loop (...) ...), do    (loop [...] ...)
    (cond [t b] [else e])       (cond t b :else e)
    (case x [(1 2) a] [else b]) (case x (1 2) a b)
    (guard (e ...) body)        (try body (catch Exception e ...))
    syntax-rules, syntax-case   a case over the patterns, measuring the templates

  Every other list is a call.  A head the walker would take for a Clojure form, like
  the loop in (let loop ...), is renamed so it stays one.")

(declare expr call-form)

(defn- like
  "form, with the position of the source form it came from."
  [source form]
  (let [m (meta source)]
    (if (and m (coll? form))
      (with-meta form m)
      form)))

(defn- exprs
  "Eagerly, so a malformed form fails inside the expr that can recover from it."
  [forms]
  (mapv expr forms))

(defn- sequence-of
  "One expression for a run of them, with no do to count when there is only one."
  [forms]
  (case (count forms)
    0 nil
    1 (expr (first forms))
    (list* 'do (exprs forms))))

;; --- parameters and bindings ---------------------------------------------

(def ^:private gambit-markers #{"#!optional" "#!key" "#!rest"})

(defn- marker? [x]
  (and (symbol? x) (contains? gambit-markers (name x))))

(defn- dot? [x]
  (and (symbol? x) (= "." (name x))))

(defn- params
  "A parameter list as a vector: (a . b) has a rest argument, and so does a lone
  symbol."
  [p]
  (cond
    (symbol? p) ['& p]
    (seq? p) (mapv #(if (dot? %) '& %) (remove marker? p))
    :else []))

(defn- binding-target
  "What a binding binds: a name, or a list of them in let-values."
  [target]
  (if (seq? target) (params target) target))

(defn- bindings
  "((a 1) (b 2)) as [a 1 b 2], so that binding a name is not read as calling it.  A
  do binding has a step too, which is code like the rest."
  [bs]
  (if (seq? bs)
    (vec (mapcat (fn [b]
                   (if (seq? b)
                     (cons (binding-target (first b)) (exprs (rest b)))
                     [b]))
                 bs))
    []))

;; --- functions -----------------------------------------------------------

(defn- arity [[ps & body]]
  (list* (params ps) (exprs body)))

(defn- lambda-form [[_ ps & body]]
  (list* 'fn (params ps) (exprs body)))

(defn- case-lambda-form [[_ & clauses]]
  (list* 'fn (mapv arity clauses)))

(defn- curried
  "(define ((f a) b) body) is f of a returning a function of b: its name, parameters
  and body, with the inner function made explicit."
  [target body]
  (if (seq? (first target))
    (curried (first target) [(list* 'lambda (rest target) body)])
    [(first target) (rest target) body]))

(defn- function-value? [value]
  (and (seq? value) (contains? '#{lambda case-lambda} (first value))))

(defn- helper
  "A named function inside another, as a letfn binding: (g [x] ...), or
  (g ([x] ...) ...) for a case-lambda.  It keeps the position of source."
  [source fname value]
  (like source
        (if (= 'lambda (first value))
          (list* fname (params (second value)) (exprs (drop 2 value)))
          (list* fname (map arity (rest value))))))

(defn- nested-define
  "A function defined inside another is a helper of its own, as if by letfn.  A
  value is just its expression."
  [[_ target & body :as form]]
  (cond
    (seq? target) (let [[fname ps inner] (curried target body)]
                    (list 'letfn [(helper form fname (list* 'lambda ps inner))]))
    (and (= 1 (count body)) (function-value? (first body)))
    (list 'letfn [(helper form target (first body))])
    :else (sequence-of body)))

;; --- control flow --------------------------------------------------------

(defn- default-clause? [clause]
  (and (seq? clause)
       (or (= 'else (first clause)) (true? (first clause)))))

(defn- cond-clause
  "A clause's test and result: a clause can be only a test, or use =>."
  [[test-form & body :as clause]]
  (cond
    (default-clause? clause) [:else (sequence-of body)]
    (= '=> (first body)) [(expr test-form) (list (expr (second body)))]
    :else [(expr test-form) (sequence-of body)]))

(defn- cond-of
  "Clauses as a cond; with only a default there is nothing left to decide."
  [clauses]
  (if (default-clause? (first clauses))
    (sequence-of (rest (first clauses)))
    (list* 'cond (vec (mapcat cond-clause (filter seq? clauses))))))

(defn- cond-form [[_ & clauses]]
  (cond-of clauses))

(defn- case-clause [[keys-form & body]]
  (if (= 'else keys-form)
    [(sequence-of body)]
    [keys-form (sequence-of body)]))

(defn- case-form [[_ subject & clauses]]
  (list* 'case (expr subject) (vec (mapcat case-clause clauses))))

(defn- guard-form
  "(guard (e clause...) body...), whose clauses are a cond run on catching."
  [[_ [e & clauses] & body]]
  (list* 'try (conj (exprs body)
                    (list 'catch 'Exception e (cond-of clauses)))))

(defn- let-form
  "A named let is a loop; any other let just binds."
  [[_ first-arg & more]]
  (if (symbol? first-arg)
    (list* 'loop (bindings (first more)) (exprs (rest more)))
    (list* 'let (bindings first-arg) (exprs more))))

(defn- helper-binding?
  "Does a binding give a name to a function?"
  [b]
  (and (seq? b) (symbol? (first b)) (= 2 (count b)) (function-value? (second b))))

(defn- letrec-form
  "letrec's functions are named helpers, as in a letfn; the rest of its bindings
  stay a let around its body."
  [[h bs & body :as form]]
  (let [fns (when (seq? bs) (filter helper-binding? bs))]
    (if (empty? fns)
      (let-form form)
      (let [others (remove helper-binding? bs)
            inner (if (seq others)
                    [(let-form (list* h (apply list others) body))]
                    (exprs body))]
        (list* 'letfn (mapv #(helper % (first %) (second %)) fns) inner)))))

(defn- do-form
  "(do bindings (test result...) body...) is a loop that tests before each pass."
  [[_ bs exit & body :as form]]
  (if (and (seq? bs) (seq? exit))
    (let [[test-form & results] exit]
      (list 'loop (bindings bs)
            (list 'if (expr test-form) (sequence-of results) (sequence-of body))))
    (call-form form)))

;; --- macros --------------------------------------------------------------

(defn- dispatch-on-patterns
  "[pattern code] pairs as a case over the patterns, which are not code.  The last
  rule is the fallthrough, so one rule has no branch at all."
  [subject pairs]
  (if (next pairs)
    (list* 'case subject (vec (concat (mapcat identity (butlast pairs))
                                      [(second (last pairs))])))
    (second (first pairs))))

(defn- syntax-rules-form [[_ _literals & rules]]
  (dispatch-on-patterns nil (mapv (fn [[pattern template]] [pattern (expr template)]) rules)))

(defn- syntax-case-form
  "A clause is [pattern output] or [pattern fender output]."
  [[_ subject _literals & clauses]]
  (dispatch-on-patterns (expr subject)
                        (mapv (fn [[pattern & code]] [pattern (sequence-of code)]) clauses)))

;; --- data ----------------------------------------------------------------

(defn- unquoted
  "The code inside a quasiquote template: what is unquoted, at any depth."
  [form]
  (cond
    (not (seq? form)) nil
    (contains? '#{unquote unquote-splicing} (first form)) [(expr (second form))]
    :else (vec (mapcat unquoted form))))

(defn- quasiquote-form [form]
  (list* 'quasiquote (unquoted (second form))))

;; --- expressions ---------------------------------------------------------

(def ^:private clojure-heads
  "Heads the walker would read as a Clojure form, but in Scheme are just calls."
  '#{loop for doseq dotimes while if-not if-let if-some when-not when-let when-some
     when-first cond-> cond->> condp try catch finally fn fn* reify proxy letfn comment
     ns defn defn- defmacro def defonce defmulti defmethod defrecord deftype
     extend-type extend-protocol deftest})

(defn- plain-head
  "A head that can't be mistaken for a Clojure form."
  [h]
  (if (contains? clojure-heads h)
    (symbol nil (str "scheme:" h))
    h))

(defn- second-of [[_ x]]
  (expr x))

(def ^:private let-heads
  '[let let* letrec letrec* let-values let*-values parameterize fluid-let with-syntax])

(def ^:private translators
  (merge
   (zipmap let-heads (repeat let-form))
   {'letrec letrec-form
    'letrec* letrec-form
    'quote identity
    'quasiquote quasiquote-form
    'syntax second-of
    'quasisyntax second-of
    'unsyntax second-of
    'unsyntax-splicing second-of
    'begin (fn [[_ & body]] (list* 'do (exprs body)))
    'define nested-define
    'lambda lambda-form
    'case-lambda case-lambda-form
    'unless (fn [[_ & args]] (list* 'when-not (exprs args)))
    'cond cond-form
    'exclusive-cond cond-form
    'case case-form
    'guard guard-form
    'do do-form
    'syntax-rules syntax-rules-form
    'syntax-case syntax-case-form}))

(defn- call-form [[h & args]]
  (list* (if (symbol? h) (plain-head h) (expr h))
         (exprs args)))

(defn- expr
  "A Scheme expression in the walker's shapes.  One that doesn't have the shape its
  head promises, as can happen in a macro template, is read as a call."
  [form]
  (if (and (seq? form) (seq form))
    (let [translate (get translators (first form) call-form)]
      (like form (try
                   (translate form)
                   (catch Exception _ (call-form form)))))
    form))

;; --- top level -----------------------------------------------------------

(defn- let-over-lambda
  "The lambda a value keeps its state around, as in (let ([cache ...]) (lambda ...))."
  [value]
  (when (and (seq? value) (contains? (set let-heads) (first value)))
    (let [lst (last value)]
      (when (function-value? lst) lst))))

(defn- function-params
  "The parameters of a lambda; a case-lambda's show in its arities instead."
  [lambda]
  (if (= 'lambda (first lambda))
    (params (second lambda))
    []))

(defn- define-value
  "(define name value): a function if the value is one or returns one, or else a
  plain def."
  [fname value]
  (cond
    (and (seq? value) (= 'lambda (first value)))
    (list* 'defn fname (params (second value)) (exprs (drop 2 value)))

    (and (seq? value) (= 'case-lambda (first value)))
    (list* 'defn fname (mapv arity (rest value)))

    (let-over-lambda value)
    (list 'defn fname (function-params (let-over-lambda value)) (expr value))

    :else (list 'def fname (expr value))))

(defn- define-unit [[_ target & body]]
  (if (seq? target)
    (let [[fname ps inner] (curried target body)]
      (list* 'defn fname (params ps) (exprs inner)))
    (define-value target (first body))))

(defn- define-syntax-unit [[_ mname transformer]]
  (list 'defmacro mname [] (expr transformer)))

(defn- define-macro-unit [[_ target & body]]
  (if (seq? target)
    (list* 'defmacro (first target) (params (rest target)) (exprs body))
    (list 'defmacro target [] (sequence-of body))))

(declare top-form)

(defn- opened [forms]
  (list* 'do (mapv top-form forms)))

(defn- module-unit
  "(module name? (exports) body...)"
  [[_ first-arg & more]]
  (opened (if (symbol? first-arg) (rest more) more)))

(defn- library-unit
  "(library (name) (export ...) (import ...) body...)"
  [[_ _name _exports _imports & body]]
  (opened body))

(def ^:private top-translators
  {'define define-unit
   'define-syntax define-syntax-unit
   'define-macro define-macro-unit
   'begin (fn [[_ & body]] (opened body))
   'module module-unit
   'library library-unit
   'eval-when (fn [[_ _situations & body]] (opened body))})

(defn- top-form
  "A top-level form: definitions become units, and containers are opened."
  [form]
  (if-let [translate (when (and (seq? form) (seq form))
                       (get top-translators (first form)))]
    (like form (try
                 (translate form)
                 (catch Exception _ (expr form))))
    (expr form)))

(defn normalize
  "Scheme's top-level forms, in the walker's shapes."
  [forms]
  (mapv top-form forms))
