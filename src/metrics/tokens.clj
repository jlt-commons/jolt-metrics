(ns metrics.tokens
  "The token stream that compression measures: code with its layout, comments
  and docstrings gone, every local renamed for where it first appears and every
  literal string or regex made one placeholder.  Two definitions that differ only
  in what they call their locals, or in the text of their strings, give the same
  tokens, so compression sees their shared shape rather than their spelling.

  Forms are in the walker's shapes, so Scheme, once metrics.scheme.normalize has
  rewritten it, tokenizes the same way.")

(defn- head-name
  "A list's head symbol, with a clojure.core qualifier removed; nil when the head
  is not a symbol."
  [form]
  (let [h (first form)]
    (when (symbol? h)
      (if (= "clojure.core" (namespace h)) (symbol (name h)) h))))

(def ^:private binding-vector-forms
  "The forms whose first argument is a [binding value ...] vector."
  '#{let let* loop loop* for doseq dotimes binding with-open with-redefs
     when-let if-let when-some if-some when-first})

(def ^:private fn-forms '#{fn fn* defn defn- defmacro})

(defn bound-symbols
  "The local names a binding pattern introduces: every plain symbol in it, as
  destructuring allows, but `&`."
  [pattern]
  (filter #(and (symbol? %) (nil? (namespace %)) (not= '& %))
          (tree-seq coll? seq pattern)))

(defn- fn-tail
  "An fn or defn's forms after its name, docstring and attr-map."
  [tail]
  (let [tail (if (symbol? (first tail)) (rest tail) tail)
        tail (if (string? (first tail)) (rest tail) tail)]
    (if (map? (first tail)) (rest tail) tail)))

(defn- arity-params
  "The params vectors of an fn's tail: one, or one per ([params] body...) arity."
  [tail]
  (if (vector? (first tail))
    [(first tail)]
    (keep #(when (and (seq? %) (vector? (first %))) (first %)) tail)))

(defn introduced
  "The locals one list form introduces itself, not those of the forms in it."
  [form]
  (let [h (head-name form)]
    (cond
      (contains? binding-vector-forms h)
      (when (vector? (second form)) (mapcat bound-symbols (take-nth 2 (second form))))
      (contains? fn-forms h)
      (concat (when (symbol? (second form)) [(second form)])
              (mapcat bound-symbols (arity-params (fn-tail (rest form)))))
      (= 'letfn h)
      (mapcat #(when (seq? %)
                 (cons (first %) (mapcat bound-symbols (arity-params (rest %)))))
              (second form))
      (= 'catch h) (bound-symbols [(nth form 2 nil)])
      :else nil)))

(defn locals
  "Every local name forms introduce, at any depth."
  [forms]
  (set (mapcat introduced (filter seq? (tree-seq coll? seq forms)))))

(defn- atom-token
  "The token of an atom: a local as itself, numbered once the whole stream is
  known; another symbol as written; a placeholder for any string or regex; other
  literals printed."
  [names x]
  (cond
    (symbol? x) (if (contains? names x) x (str x))
    (string? x) "\"\""
    (or (keyword? x) (number? x) (boolean? x) (nil? x) (char? x)) (pr-str x)
    :else "#\"\""))

(defn- drop-docstring
  "A defn-like form without its docstring and attr-map, which are not code."
  [form]
  (if (contains? '#{defn defn- defmacro} (head-name form))
    (concat (take 2 form) (fn-tail (rest form)))
    form))

(defn- walk
  "acc with the tokens of x after it, locals left as their symbols."
  [names acc x]
  (let [around (fn [open xs close]
                 (conj (reduce #(walk names %1 %2) (conj acc open) xs) close))]
    (cond
      (and (seq? x) (= 'comment (head-name x))) acc
      (seq? x) (around "(" (drop-docstring x) ")")
      (vector? x) (around "[" x "]")
      (map? x) (around "{" (mapcat identity x) "}")
      (set? x) (around "#{" x "}")
      :else (conj acc (atom-token names x)))))

(defn code-tokens
  "The token stream of a definition's params vectors and then its forms, in
  order: brackets as tokens, locals numbered by first appearance as $0, $1 and
  so on, strings and regexes as placeholders, comment forms left out.  The
  locals are the names params and the binding forms in forms introduce."
  [params forms]
  (let [names (into (locals forms) (mapcat bound-symbols params))
        ts (reduce #(walk names %1 %2) [] (concat params forms))
        ids (zipmap (distinct (filter symbol? ts)) (range))]
    (mapv #(if (symbol? %) (str "$" (get ids %)) %) ts)))
