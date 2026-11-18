(ns metrics.egraph
  "An e-graph: a set of terms closed under equalities, kept compact by sharing.
  Each e-class is a set of e-nodes that are all equal; an e-node is an operator
  and its children's e-classes, so one e-class stands for every term any of its
  nodes can build.

  The design follows egg (Willsey, Nandi, Wang, Flatt, Tatlock and Panchekha,
  egg: Fast and extensible equality saturation, POPL 2021):

    - a union-find over class ids, with a hashcons from canonical e-node to id;
    - union only records the merge and queues the class, and rebuild restores
      the congruence invariant afterwards (a = b implies f(a) = f(b)) by
      re-canonicalizing each queued class's parents, merging any that collide;
    - an e-class analysis, data kept per class and joined on union.

  The analysis here is what abstraction search needs: how many times a class
  occurs in the source (summed on union, since merged terms are the same code
  written two ways), whether it mentions a local, and which definitions it
  occurs in.

  The graph is a persistent value.  Union is by size, so find walks a path of
  at most log n links without compressing it.

  A term is [op child ...], op a vector label such as [:list 3] or [:sym \"inc\"],
  or [:class id] for an existing class.  Terms from the source go in through
  add-term, which counts them; terms rewrites derive go in through add-derived,
  which does not.  A class remembers which of its nodes came from the source, so
  extraction can prefer code as it was written to what a rewrite made of it."
  (:refer-clojure :exclude [find]))

(def empty-graph
  "An e-graph with nothing in it.  Classes record the definitions they occur in
  only for compound terms, so leaves stay cheap.  Not by size: a rewrite can make
  a small term equal to a larger one, and every occurrence of the class counts."
  {:parent {} :memo {} :classes {} :pending [] :next 0})

(defn find
  "The canonical id of id's class."
  [g id]
  (let [p (get-in g [:parent id])]
    (if (= p id) id (recur g p))))

(defn- canon
  "An e-node with its children made canonical."
  [g node]
  (into [(first node)] (map #(find g %)) (subvec node 1)))

(defn- class-of [g id] (get-in g [:classes (find g id)]))

(defn nodes
  "The e-nodes of id's class."
  [g id]
  (:nodes (class-of g id)))

(defn occurrences
  "How many times id's class occurs in the source terms."
  [g id]
  (:occ (class-of g id) 0))

(defn local?
  "Does id's class mention a local?"
  [g id]
  (:local? (class-of g id)))

(defn units
  "The definitions id's class occurs in, one entry per occurrence; empty for a
  class too small to track."
  [g id]
  (:units (class-of g id) []))

(defn source-node?
  "Did node, canonical, come from the source rather than a rewrite?"
  [g id node]
  (contains? (:source (class-of g id)) node))

(defn class-ids
  "Every canonical class id."
  [g]
  (keys (:classes g)))

(defn- node-local? [g node]
  (or (= :local (first (first node)))
      (boolean (some #(local? g %) (subvec node 1)))))

(defn- add-node
  "[g id] with the canonical e-node in its class, creating the class if new."
  [g node]
  (let [node (canon g node)]
    (if-let [id (get-in g [:memo node])]
      [g (find g id)]
      (let [id (:next g)
            g (reduce (fn [g kid] (update-in g [:classes kid :parents] conj [node id]))
                      g (distinct (subvec node 1)))]
        [(-> g
             (assoc :next (inc id))
             (assoc-in [:parent id] id)
             (assoc-in [:memo node] id)
             (assoc-in [:classes id] {:nodes #{node} :parents [] :occ 0 :source #{}
                                      :local? (node-local? g node) :units []}))
         id]))))

(defn- count-occurrence
  "g with one more occurrence of node in id's class, noted as in unit when unit
  is given."
  [g id node unit]
  (let [id (find g id)]
    (cond-> (-> g
                (update-in [:classes id :occ] inc)
                (update-in [:classes id :source] conj (canon g node)))
      unit (update-in [:classes id :units] conj unit))))

(defn- add* [g term counted? unit]
  (if (= :class (first term))
    [g (find g (second term)) 0]
    (let [[g kids size] (reduce (fn [[g kids size] t]
                                  (let [[g id n] (add* g t counted? unit)]
                                    [g (conj kids id) (+ size n)]))
                                [g [] 1]
                                (rest term))
          node (into [(first term)] kids)
          [g id] (add-node g node)]
      [(if counted? (count-occurrence g id node (when (> size 1) unit)) g)
       id size])))

(defn add-term
  "[g id] with a source term added and every class it reaches counted once more,
  noted as occurring in unit when given."
  ([g term] (add-term g term nil))
  ([g term unit]
   (let [[g id] (add* g term true unit)] [g id])))

(defn add-derived
  "[g id] with a term a rewrite derived added, counting nothing."
  [g term]
  (let [[g id] (add* g term false nil)] [g id]))

(defn- merge-classes
  "One class holding two: the analysis joined, occurrences summed."
  [r o]
  {:nodes (into (:nodes r) (:nodes o))
   :parents (into (:parents r) (:parents o))
   :occ (+ (:occ r) (:occ o))
   :local? (or (:local? r) (:local? o))
   :source (into (:source r) (:source o))
   :units (into (:units r) (:units o))})

(defn union
  "g with a's and b's classes merged.  Congruence holds again only after
  rebuild."
  [g a b]
  (let [a (find g a) b (find g b)]
    (if (= a b)
      g
      (let [ca (get-in g [:classes a]) cb (get-in g [:classes b])
            [root other] (if (>= (+ (count (:nodes ca)) (count (:parents ca)))
                                 (+ (count (:nodes cb)) (count (:parents cb))))
                           [a b] [b a])
            merged (merge-classes (get-in g [:classes root]) (get-in g [:classes other]))]
        (-> g
            (assoc-in [:parent other] root)
            (update :classes dissoc other)
            (assoc-in [:classes root] merged)
            (update :pending conj root))))))

(defn- repair
  "g with the parents of id's class re-canonicalized: stale hashcons entries
  replaced, and parents that are now the same e-node merged."
  [g id]
  (let [id (find g id)
        parents (get-in g [:classes id :parents])
        g (reduce (fn [g [node pid]]
                    (-> g
                        (update :memo dissoc node)
                        (assoc-in [:memo (canon g node)] (find g pid))))
                  g parents)
        [g seen] (reduce (fn [[g seen] [node pid]]
                           (let [node (canon g node)
                                 g (if-let [other (get seen node)] (union g other pid) g)]
                             [g (assoc seen node (find g pid))]))
                         [g {}] parents)
        id (find g id)]
    (assoc-in g [:classes id :parents] (vec seen))))

(defn- canonical-nodes
  "g with every class's nodes canonical and deduplicated."
  [g]
  (update g :classes
          (fn [cs] (into {} (map (fn [[id c]] [id (-> c
                                                     (update :nodes #(into #{} (map (partial canon g)) %))
                                                     (update :source #(into #{} (map (partial canon g)) %)))])) cs))))

(defn rebuild
  "g with congruence restored after unions."
  [g]
  (loop [g g]
    (if (seq (:pending g))
      (let [todo (distinct (map #(find g %) (:pending g)))]
        (recur (reduce repair (assoc g :pending []) todo)))
      (canonical-nodes g))))

;; --- extraction --------------------------------------------------------------

(def ^:private derived-cost
  "What a node a rewrite made costs over one from the source, so that of two
  equally small forms extraction picks the one as written."
  0.25)

(defn- cheaper?
  "Is [c node] a better choice than the entry best?  Ties go to the smaller
  hash, so the choice does not depend on the order nodes are seen in."
  [c node best]
  (or (nil? best)
      (< c (first best))
      (and (== c (first best)) (< (hash node) (hash (second best))))))

(declare visit)

(defn- node-cost
  "A node's cost given its children's, or nil when a child has none yet."
  [st path {:keys [source]} node]
  (reduce (fn [acc kid]
            (if-let [[c] (visit st kid path)] (+ acc c) (reduced nil)))
          (if (contains? source node) 1.0 (+ 1.0 derived-cost))
          (subvec node 1)))

(defn- visit
  "[cost node] for id's class, costing it and the classes under it into the
  volatile (:best st); nil when id is already on path."
  [{:keys [g best] :as st} id path]
  (let [id (find g id)]
    (or (get @best id)
        (when-not (contains? path id)
          (let [c (get-in g [:classes id])
                path (conj path id)
                choice (reduce (fn [b node]
                                 (let [cost (node-cost st path c node)]
                                   (if (and cost (cheaper? cost node b)) [cost node] b)))
                               nil (:nodes c))]
            (when choice (vswap! best assoc id choice))
            choice)))))

(defn extraction
  "The cheapest e-node of every class, counting one per node and a little more
  for a derived one: {id [cost node]}.  One depth-first pass, each class costed
  once; a node that leads back into a class still being costed is passed over,
  so a cycle never makes a term infinite."
  [g]
  (let [best (volatile! {})]
    (doseq [id (class-ids g)] (visit {:g g :best best} id #{}))
    @best))

(defn best-term
  "The cheapest term id's class holds, as [op child-term ...]; with an
  extraction already computed, reuse it."
  ([g id] (best-term g (extraction g) id))
  ([g ex id]
   (let [node (second (get ex (find g id)))]
     (into [(first node)] (map #(best-term g ex %)) (subvec node 1)))))
