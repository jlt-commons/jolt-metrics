(ns metrics.mentions
  "What read forms mention, for the cleanup candidates: how often each symbol
  name occurs, the namespaces and aliases they qualify through, and the
  qualified symbols written out in full.  Quoted forms count, since a quoted
  symbol can still name a var.")

(def alias-placeholder
  "What an auto-resolved alias a resolves to is this followed by a."
  "metrics.read-alias.")

(defn- atoms [form]
  (remove coll? (tree-seq coll? seq form)))

(defn symbol-counts
  "How many times each symbol name occurs in forms, whatever its namespace."
  [forms]
  (frequencies (map name (filter symbol? (mapcat atoms forms)))))

(defn- alias-qualifier
  "The alias a placeholder namespace stands for, or nil if it is not one."
  [ns-name]
  (when (and ns-name (.startsWith ns-name alias-placeholder))
    (subs ns-name (count alias-placeholder))))

(defn qualifiers
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

(defn qualified-symbols
  "The ns/name text of the namespace-qualified symbols in forms, quoted ones
  included, as `(requiring-resolve 'a.b/f)` names a var.  Aliased ones are left
  to the call graph, which resolves them."
  [forms]
  (set (for [a (mapcat atoms forms)
             :when (and (symbol? a) (namespace a) (not (alias-qualifier (namespace a))))]
         (str a))))
