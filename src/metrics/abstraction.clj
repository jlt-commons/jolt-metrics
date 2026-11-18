(ns metrics.abstraction
  "Abstraction opportunities: code shapes that recur often enough that pulling
  them out as a function would make the project smaller.  Where near-duplicates
  say two definitions are alike, this says what they share, with holes for
  where they differ, and how much extracting it would save.

  The method follows babble (Cao, Kunkel, Nandi, Willsey, Tatlock and
  Polikarpova, babble: Learning better abstractions with e-graphs and
  anti-unification, POPL 2023):

    1. Every definition goes into one e-graph (metrics.egraph), so shared
       subterms are one class, and the e-graph is saturated with rewrites
       that only undo syntactic sugar: the threading macros, when, when-not,
       if-not and an explicit nil else.  An e-class then holds a form as
       written and as expanded, and two definitions that differ only in how
       they were sugared meet.

    2. Pairs of e-classes are anti-unified.  First-order anti-unification
       (Plotkin, A note on inductive generalization, 1970) finds the least
       general pattern two terms share; over an e-graph each pair of
       same-operator e-nodes is tried, so the pattern can be found through
       any of the forms the rewrites made equal.  As in Plotkin, the same
       pair of subterms is always the same variable.  Results are memoized
       per pair of classes and cut to a beam of the most specific.

       A local is never left concrete: its name means nothing outside the
       definition that binds it.  Locals become variables, and a variable in
       a binding position is a binder of the extracted function, not one of
       its parameters.

    3. Each pattern is matched back over the e-graph to count its uses, and
       scored as Stitch does (Bowers, Olausson, Wong, Grand, Tenenbaum, Ellis
       and Solar-Lezama, Top-down synthesis for library learning, POPL 2023):
       each use saves the pattern's size less a call, and the definition
       costs the pattern, its parameters and a little overhead.

  Comparing every pair of classes is quadratic, so pairs are only made inside
  a bucket of classes with the same operator and head, sorted by a sketch of
  their shape, each paired with its next few neighbours: the sorted
  neighbourhood method of record linkage (Hernandez and Stolfo, The
  merge/purge problem for large databases, 1995).  Copies sort together, so
  a pattern used often is very likely to have some pair of its uses compared."
  (:require [clojure.edn :as edn]
            [metrics.egraph :as eg]
            [metrics.tokens :as tokens]))

(def defaults
  "min-size: the fewest nodes a pattern needs to be worth naming.  max-arity:
  the most parameters it may take.  window: how many sorted neighbours each
  class is paired with.  beam: patterns kept per pair of classes.  depth: how
  deep anti-unification goes.  max-iters: saturation rounds."
  {:min-size 8 :max-arity 4 :window 8 :beam 3 :depth 12 :max-iters 6 :top 25})

;; --- forms as terms ----------------------------------------------------------

(defn- sym-name [s]
  (if (= "clojure.core" (namespace s)) (name s) (str s)))

(defn- comment-form? [x]
  (and (seq? x) (symbol? (first x)) (= "comment" (sym-name (first x)))))

(defn- elements
  "A collection's kind and the elements a term holds, in a fixed order: fn* as
  fn, comment forms dropped, map entries and set members sorted."
  [x]
  (cond
    (seq? x) [:list (remove comment-form? (if (= 'fn* (first x)) (cons 'fn (rest x)) x))]
    (vector? x) [:vec x]
    (map? x) [:map (mapcat identity (sort-by (comp pr-str key) x))]
    :else [:set (sort-by pr-str x)]))

(defn- atom-term [locals x]
  (cond
    (symbol? x) (if (contains? locals x) [[:local (str x)]] [[:sym (sym-name x)]])
    (string? x) [[:str x]]
    (or (keyword? x) (number? x) (boolean? x) (nil? x) (char? x)) [[:lit (pr-str x)]]
    :else [[:re (str x)]]))

(defn encode
  "A form as a term for the e-graph; the symbols in locals as locals.  Strings
  and regexes keep their text: unlike compression, which blanks them, an
  abstraction has to take a differing string as a parameter.  fn* reads as fn; map
  entries and set members are sorted, so their order does not matter."
  [locals x]
  (if (coll? x)
    (let [[kind xs] (elements x)]
      (into [[kind (count xs)]] (map #(encode locals %)) xs))
    (atom-term locals x)))

;; --- the sugar rules ---------------------------------------------------------

(defn- syms
  "The symbol names id's class holds."
  [g id]
  (into #{} (keep (fn [n] (when (= :sym (ffirst n)) (second (first n))))) (eg/nodes g id)))

(defn- list-nodes [g id]
  (sort-by pr-str (filter #(= :list (ffirst %)) (eg/nodes g id))))

(defn- nil-class? [g id]
  (contains? (eg/nodes g id) [[:lit "nil"]]))

(defn- cls [id] [:class id])

(defn- call [head & args]
  (into [[:list (inc (count args))] [[:sym head]]] args))

(defn- step
  "acc threaded into one step of -> (or ->> when last?)."
  [g s acc last?]
  (if-let [n (first (list-nodes g s))]
    (let [[f & args] (map cls (subvec n 1))
          xs (if last? (concat [f] args [acc]) (concat [f acc] args))]
      (into [[:list (count xs)]] xs))
    [[:list 2] (cls s) acc]))

(defn- thread [g [x & steps] last?]
  (reduce (fn [acc s] (step g s acc last?)) (cls x) steps))

(defn- one-armed-if [c body]
  (call "if" c (if (next body) (apply call "do" body) (first body))))

(def ^:private sugar
  "By head: the term a call equals with that sugar undone, or nil when the rule
  does not apply.  Each takes the graph and the call's argument classes, at least
  two of them."
  {"->" (fn [g kids] (thread g kids false))
   "->>" (fn [g kids] (thread g kids true))
   "when" (fn [_ [c & body]] (one-armed-if (cls c) (map cls body)))
   "when-not" (fn [_ [c & body]] (apply call "when" (call "not" (cls c)) (map cls body)))
   "if-not" (fn [_ [c & body]]
              (when (<= (count body) 2) (apply call "if" (call "not" (cls c)) (map cls body))))
   "if" (fn [g [c a e :as kids]]
          (when (and (= 3 (count kids)) (nil-class? g e)) (call "if" (cls c) (cls a))))})

(defn- rewrites
  "The terms an e-node equals by undoing one piece of sugar."
  [g node]
  (let [[op h & kids] node]
    (when (and (= :list (first op)) h (>= (count kids) 2))
      (keep (fn [head] (when-let [rule (sugar head)] (rule g kids))) (syms g h)))))

(defn saturate
  "g with the sugar rules applied until nothing new comes of them, or for
  max-iters rounds."
  [g opts]
  (let [max-iters (:max-iters opts (:max-iters defaults))]
    (loop [g g i 0]
      (let [work (for [id (eg/class-ids g) n (eg/nodes g id) t (rewrites g n)] [id t])
            g' (eg/rebuild (reduce (fn [g [id t]]
                                     (let [[g nid] (eg/add-derived g t)] (eg/union g id nid)))
                                   g work))]
        (if (or (>= (inc i) max-iters)
                (and (= (:next g) (:next g')) (= (count (:classes g)) (count (:classes g')))))
          g'
          (recur g' (inc i)))))))

;; --- anti-unification --------------------------------------------------------

(defn- term-size [t]
  (reduce + 1 (map term-size (rest t))))

(defn- class-size
  "The node count of the cheapest form of id's class."
  [{:keys [g ex sizes]} id]
  (let [id (eg/find g id)]
    (or (get @sizes id)
        (let [n (term-size (eg/best-term g ex id))]
          (swap! sizes assoc id n)
          n))))

(def ^:private derived-weight
  "What a pattern node counts for when it was matched through a rewrite on
  either side rather than as written.  An expansion is a node or two larger than
  its sugar, so counted at all it would win as the more specific pattern, and the
  report would show code nobody wrote.  At nothing, an expansion and its sugar
  tie and the sugar, found first, is kept; the saving stays a lower bound."
  0)

(defn- pattern-size
  "A pattern's concrete nodes: variables are free, and a node only a rewrite
  made counts derived-weight."
  [ctx p]
  (case (first p)
    (:hole :var) 0
    :class (class-size ctx (second p))
    (reduce + (if (::derived (meta p)) derived-weight 1) (map #(pattern-size ctx %) (rest p)))))

(defn- holes [p]
  (if (#{:hole :var} (first p))
    #{p}
    (if (= :class (first p)) #{} (into #{} (mapcat holes) (rest p)))))

(defn- best
  "The beam most specific patterns: most concrete nodes, then fewest variables.
  Stable, so of equals the first found stays."
  [ctx ps]
  (->> (distinct ps)
       (sort-by (fn [p] [(- (pattern-size ctx p)) (count (holes p))]))
       (take (:beam ctx))
       vec))

(declare au)

(defn- ordered-nodes
  "id's nodes, those from the source first."
  [g id]
  (sort-by (fn [n] [(if (eg/source-node? g id n) 0 1) (pr-str n)]) (eg/nodes g id)))

(defn- au-pair
  "The patterns two e-nodes with the same operator share: the operator over each
  pair of children's patterns, cut to the beam as each child is added.  A node
  either side got from a rewrite is marked derived."
  [{:keys [g] :as ctx} depth [a na] [b nb]]
  (let [derived? (not (and (eg/source-node? g a na) (eg/source-node? g b nb)))]
    (reduce (fn [combos [ka kb]]
              (let [ps (au ctx (dec depth) ka kb)]
                (best ctx (for [c combos p ps] (conj c p)))))
            [(with-meta [(first na)] (when derived? {::derived true}))]
            (map vector (subvec na 1) (subvec nb 1)))))

(defn- au-nodes
  "The patterns two classes share through a pair of their e-nodes with the same
  operator.  Source nodes come first, so of two equal patterns the one as
  written is kept."
  [{:keys [g] :as ctx} depth a b]
  (best ctx
        (for [na (ordered-nodes g a)
              nb (ordered-nodes g b)
              :when (and (= (first na) (first nb)) (not= :local (ffirst na)))
              p (au-pair ctx depth [a na] [b nb])]
          p)))

(defn- au
  "The patterns classes a and b share, the variable [:hole a b] always among
  them.  A class with no locals that is shared outright stays concrete."
  [{:keys [g memo] :as ctx} depth a b]
  (let [a (eg/find g a) b (eg/find g b)]
    (if (and (= a b) (not (eg/local? g a)))
      [(cls a)]
      (or (get @memo [a b])
          (let [r (if (pos? depth)
                    (best ctx (conj (au-nodes ctx depth a b) [:hole a b]))
                    [[:hole a b]])]
            (swap! memo assoc [a b] r)
            r)))))

(defn- context [g opts]
  {:g g :ex (eg/extraction g) :sizes (atom {}) :memo (atom {})
   :beam (:beam opts (:beam defaults))
   :depth (:depth opts (:depth defaults))})

(defn- anti-unify*
  "The patterns classes a and b share, rooted in a node: never a bare variable,
  and never a class kept whole."
  [ctx a b]
  (au-nodes ctx (:depth ctx) (eg/find (:g ctx) a) (eg/find (:g ctx) b)))

(defn anti-unify
  "The most specific patterns classes a and b share, rooted in a node."
  [g a b opts]
  (anti-unify* (context g opts) a b))

;; --- patterns as forms -------------------------------------------------------

(defn- number-holes
  "A pattern with its holes numbered [:var i] by first appearance."
  [p]
  (let [seen (atom {})
        walk (fn walk [p]
               (case (first p)
                 :hole (or (get @seen p)
                           (let [v [:var (count @seen)]] (swap! seen assoc p v) v))
                 :class p
                 (with-meta (into [(first p)] (map walk) (rest p)) (meta p))))]
    (walk p)))

(defn- var-symbol [i]
  (symbol (str "?" (if (< i 26) (char (+ 97 i)) (str "v" i)))))

(defn- form-of
  "The Clojure form of a term or pattern node, its children already forms."
  [[kind x] kids]
  (case kind
    :list (apply list kids)
    :vec (vec kids)
    :map (apply array-map kids)
    :set (set kids)
    (:sym :local) (symbol x)
    :str x
    :re (symbol (str "#\"" x "\""))
    :lit (edn/read-string x)))

(defn- term-form [t]
  (form-of (first t) (map term-form (rest t))))

(defn- pattern-form [{:keys [g ex]} p]
  (case (first p)
    :var (var-symbol (second p))
    :hole (symbol (str "?" (second p) "-" (nth p 2)))
    :class (term-form (eg/best-term g ex (second p)))
    (form-of (first p) (map #(pattern-form {:g g :ex ex} %) (rest p)))))

(defn render
  "A pattern as a Clojure form, its variables ?a, ?b and so on."
  ([g p] (render g (eg/extraction g) p))
  ([g ex p] (pattern-form {:g g :ex ex} (number-holes p))))

(defn- binders
  "The variables a pattern's form binds: they name a local of the extracted
  function rather than one of its parameters."
  [form]
  (set (mapcat tokens/introduced (filter seq? (tree-seq coll? seq form)))))

(defn- variable? [x]
  (and (symbol? x) (= \? (first (str x)))))

(defn- variables [form]
  (set (filter variable? (tree-seq coll? seq form))))

(def ^:private binding-heads
  '#{let let* loop loop* for doseq dotimes binding with-open with-redefs
     when-let if-let when-some if-some when-first letfn})

(defn- malformed?
  "Does the pattern put a variable where a binding vector or params vector goes?
  That is a macro's job, not a function's."
  [form]
  (some (fn [f]
          (let [h (first f)]
            (or (and (contains? binding-heads h) (variable? (second f)))
                (and (contains? '#{fn fn*} h)
                     (or (variable? (second f))
                         (and (symbol? (second f)) (variable? (nth f 2 nil))))))))
        (filter seq? (tree-seq coll? seq form))))

;; --- matching ----------------------------------------------------------------

(defn- match
  "The substitutions under which pattern p matches class c, extending s."
  [g p c s]
  (case (first p)
    :var (let [c (eg/find g c)]
           (if-let [b (get s (second p))]
             (when (= b c) [s])
             [(assoc s (second p) c)]))
    :class (when (= (eg/find g (second p)) (eg/find g c)) [s])
    (for [n (eg/nodes g c)
          :when (= (first n) (first p))
          s' (reduce (fn [ss [kp kc]] (mapcat #(match g kp kc %) ss))
                     [s]
                     (map vector (rest p) (subvec n 1)))]
      s')))

(defn- node-key
  "Where a node is indexed: its operator, and for a list its head's class."
  [g n]
  (if (= :list (ffirst n))
    [(first n) (some->> (second n) (eg/find g))]
    [(first n)]))

(defn- pattern-key [g p]
  (if (= :list (ffirst p))
    (when (= :class (first (second p))) [(first p) (eg/find g (second (second p)))])
    [(first p)]))

(defn- node-index [g ids]
  (reduce (fn [m id]
            (reduce (fn [m n] (update m (node-key g n) (fnil conj #{}) id)) m (eg/nodes g id)))
          {} ids))

;; --- candidates --------------------------------------------------------------

(defn- sketch
  "A class's shape to depth d, locals blanked, for sorting copies together."
  [{:keys [g ex]} id d]
  (let [n (second (get ex (eg/find g id)))
        op (first n)]
    (cond
      (= :local (first op)) "_"
      (zero? d) "."
      :else (str op (mapv #(sketch {:g g :ex ex} % (dec d)) (subvec n 1))))))

(defn- candidate-pairs
  "Pairs of classes worth anti-unifying: a class with itself when it occurs
  twice, and each class with its next window neighbours in its bucket."
  [{:keys [g] :as ctx} ids window]
  (let [index (node-index g ids)]
    (distinct
     (concat
      (for [id ids :when (>= (eg/occurrences g id) 2)] [id id])
      (for [[_ bucket] index
            :let [sorted (vec (sort-by #(sketch ctx % 3) bucket))]
            i (range (count sorted))
            j (range (inc i) (min (count sorted) (+ i 1 window)))]
        [(sorted i) (sorted j)])))))

;; --- scoring -----------------------------------------------------------------

(defn utility
  "What extracting a pattern of size nodes and arity parameters, used uses
  times, saves: each use becomes a call of two nodes, its arguments staying;
  the definition costs the pattern, a node per parameter and three more for
  defn, its name and its parameter vector."
  [size arity uses]
  (- (* uses (- size 2)) (+ size arity 3)))

(defn- site [units u]
  (select-keys (nth units u) [:path :name :line]))

(defn- class-tree
  "Every class in the cheapest form of id's class, id first."
  [{:keys [g ex]} id]
  (let [id (eg/find g id)]
    (cons id (mapcat #(class-tree {:g g :ex ex} %) (subvec (second (get ex id)) 1)))))

(defn- consumed
  "The classes a match of p at c takes up, one entry per occurrence: c and those
  under p's concrete nodes, not those its variables bind.  When several nodes of
  c match, the first does."
  [{:keys [g] :as ctx} p c]
  (case (first p)
    :var []
    :class (class-tree ctx c)
    (let [n (first (filter (fn [n] (and (= (first n) (first p))
                                        (every? (fn [[kp kc]] (seq (match g kp kc {})))
                                                (map vector (rest p) (subvec n 1)))))
                           (eg/nodes g c)))]
      (cons (eg/find g c) (mapcat #(consumed ctx %1 %2) (rest p) (subvec n 1))))))

(defn- shape
  "A pattern's form, size, arity and index key, or nil when it cannot be a
  function or is too small or takes too many parameters to be worth one."
  [{:keys [g opts] :as ctx} p]
  (let [form (pattern-form ctx p)
        size (pattern-size ctx p)
        arity (count (remove (binders form) (variables form)))
        key (pattern-key g p)]
    (when (and key
               (not (malformed? form))
               (>= size (:min-size opts))
               (<= arity (:max-arity opts)))
      {:pattern form :size size :arity arity :key key})))

(defn- scored
  "A pattern with the classes it matches and the code each match takes up, or
  nil if it is not worth naming."
  [{:keys [g index] :as ctx} p]
  (when-let [{:keys [size arity key] :as o} (shape ctx p)]
    (let [matched (filter #(and (pos? (eg/occurrences g %)) (seq (match g p % {})))
                          (get index key))
          uses (reduce + 0 (map #(eg/occurrences g %) matched))]
      (when (and (>= uses 2) (pos? (utility size arity uses)))
        (assoc (dissoc o :key)
               :matched (vec matched)
               :consumed (into {} (map (fn [m] [m (consumed ctx p m)])) matched))))))

(defn- left
  "How many of c's occurrences are not yet spent."
  [g spent c]
  (max 0 (- (eg/occurrences g c) (get spent c 0))))

(defn- rescore
  "A candidate scored on the code not yet spent, or nil if that is no longer
  worth naming."
  [g spent o]
  (let [live (filter #(pos? (left g spent %)) (:matched o))
        uses (reduce + 0 (map #(left g spent %) live))
        saving (utility (:size o) (:arity o) uses)]
    (when (and (>= uses 2) (pos? saving))
      (assoc o :uses uses :saving saving :live live))))

(defn- spend
  "spent with the code a chosen candidate's live matches take up added."
  [g spent o]
  (reduce (fn [spent m]
            (let [k (left g spent m)]
              (reduce #(update %1 %2 (fnil + 0) k) spent (get (:consumed o) m))))
          spent (:live o)))

(defn- reported [g units o]
  {:pattern (:pattern o) :size (Math/round (double (:size o))) :arity (:arity o)
   :uses (:uses o) :saving (Math/round (double (:saving o)))
   :sites (vec (distinct (map #(site units %) (mapcat #(eg/units g %) (:live o)))))})

(defn- select
  "Up to top opportunities, greedily: the one saving most is taken, the code its
  uses take up is spent, and the rest are scored again on what is left.  This
  stands in for Stitch's rewriting of the corpus after each abstraction: a
  generalization of a taken pattern, or a piece of it, matches only spent code
  and drops out."
  [g units candidates top]
  (loop [chosen [] spent {} pool candidates]
    ;; stable: of equal savings the pattern found first, the one as written
    (let [best (first (sort-by (comp - :saving) (keep #(rescore g spent %) pool)))]
      (if (or (nil? best) (>= (count chosen) top))
        (mapv #(reported g units %) chosen)
        (recur (conj chosen best)
               (spend g spent best)
               (remove #(identical? (:pattern %) (:pattern best)) pool))))))

(defn- unit-graph
  "The e-graph of every unit's body, each form counted and noted as in its unit."
  [units min-size]
  (reduce-kv
   (fn [g i u]
     (let [locals (into (tokens/locals (:body u)) (mapcat tokens/bound-symbols (:param-vectors u)))]
       (reduce (fn [g form] (first (eg/add-term g (encode locals form) i)))
               g (:body u))))
   (assoc eg/empty-graph :unit-min min-size)
   (vec units)))

(defn opportunities
  "The abstractions worth extracting from units, most saving first.  A unit is
  {:path :name :line :param-vectors :body}.  Each opportunity is {:pattern
  :size :arity :uses :saving :sites}, sites the definitions it occurs in."
  [units opts]
  (let [opts (merge defaults opts)
        units (vec units)
        g (saturate (unit-graph units (:min-size opts)) opts)
        ctx (context g opts)
        ids (filter #(and (pos? (eg/occurrences g %))
                          (>= (class-size ctx %) (:min-size opts)))
                    (eg/class-ids g))
        ctx (assoc ctx :index (node-index g (eg/class-ids g)) :opts opts)
        patterns (distinct (map number-holes (mapcat (fn [[a b]] (anti-unify* ctx a b))
                                                     (candidate-pairs ctx ids (:window opts)))))]
    (select g units (keep #(scored ctx %) patterns) (:top opts))))
