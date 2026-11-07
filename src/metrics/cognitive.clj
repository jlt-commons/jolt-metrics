(ns metrics.cognitive
  "Cognitive complexity.

  A function body is reduced to a tagged tree with fixed branching, so the
  recursion is structural:
    [:Leaf]              -- a literal or symbol, or the end of a sequence
    [:Seq head tail]     -- a sequence of forms (head, then the rest)
    [:If test then else] -- a conditional; else is [:Else e] or [:Leaf]
    [:Else inner]        -- an else branch
    [:Nest body]         -- cond/case/loop/catch: adds 1, body nested
    [:Flat body]         -- a logical operator run: adds a flat 1
    [:Deep body]         -- a closure: adds nothing, body nested

  :If and :Nest add 1 plus their nesting depth; an else-if (an :Else wrapping an
  :If) adds a flat 1; every other form just descends.")

(defn complexity-at
  "Cognitive complexity of node, which sits at nesting depth."
  [node depth]
  (let [inner (inc depth)]
    (case (first node)
      :Leaf 0
      :Seq (let [[_ head tail] node]
             (+ (complexity-at head depth) (complexity-at tail depth)))
      :If (let [[_ test then else-node] node]
            (+ 1 depth (complexity-at test inner) (complexity-at then inner)
               (complexity-at else-node depth)))
      :Else (let [[_ else-body] node]
              (case (first else-body)
                :If (let [[_ test then else-node] else-body]
                      (+ 1 (complexity-at test inner) (complexity-at then inner)
                         (complexity-at else-node depth)))
                (complexity-at else-body inner)))
      :Nest (let [[_ body] node] (+ 1 depth (complexity-at body inner)))
      :Flat (let [[_ body] node] (+ 1 (complexity-at body depth)))
      :Deep (let [[_ body] node] (complexity-at body inner)))))

(defn complexity
  "Cognitive complexity of a function body tree."
  [node]
  (complexity-at node 0))

(defn nesting-at
  "The deepest nesting level under node, which sits at nesting depth."
  [node depth]
  (let [inner (inc depth)]
    (case (first node)
      :Leaf 0
      :Seq (let [[_ head tail] node]
             (max (nesting-at head depth) (nesting-at tail depth)))
      :If (let [[_ test then else-node] node]
            (max inner (nesting-at test inner) (nesting-at then inner)
                 (nesting-at else-node depth)))
      :Else (let [[_ else-body] node]
              (case (first else-body)
                :If (nesting-at else-body depth)
                (nesting-at else-body inner)))
      :Nest (let [[_ body] node] (max inner (nesting-at body inner)))
      :Flat (let [[_ body] node] (nesting-at body depth))
      :Deep (let [[_ body] node] (nesting-at body inner)))))

(defn max-nesting
  "How deeply a function body's control structures nest; a closure counts as a
  level for what it holds."
  [node]
  (nesting-at node 0))
