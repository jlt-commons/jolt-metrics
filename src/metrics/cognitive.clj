(ns metrics.cognitive
  "Cognitive complexity.

  A function body is reduced to a tagged tree with fixed branching, so the
  recursion is structural:
    [:Leaf]              -- a literal or symbol, or the end of a sequence
    [:Seq head tail]     -- a sequence of forms (head, then the rest)
    [:If test then else] -- a conditional; else is [:Else e] or [:Leaf]
    [:Else inner]        -- an else branch
    [:Nest body]         -- switch/cond/loop/try/catch: adds 1, body nested

  :If and :Nest add 1 plus their nesting depth; an else-if (an :Else wrapping
  an :If) sits at the same depth as the if it continues; every other form just
  descends.")

(defn complexity-at
  "Cognitive complexity of node, which sits at nesting depth."
  [node depth]
  (case (first node)
    :Leaf 0
    :Seq (let [[_ head tail] node]
           (+ (complexity-at head depth) (complexity-at tail depth)))
    :If (let [[_ test then else-node] node]
          (+ 1 depth
             (complexity-at test (inc depth))
             (complexity-at then (inc depth))
             (complexity-at else-node depth)))
    :Else (let [[_ inner] node]
            (case (first inner)
              :If (complexity-at inner depth)
              (complexity-at inner (inc depth))))
    :Nest (let [[_ body] node]
            (+ 1 depth (complexity-at body (inc depth))))))

(defn complexity
  "Cognitive complexity of a function body tree."
  [node]
  (complexity-at node 0))
