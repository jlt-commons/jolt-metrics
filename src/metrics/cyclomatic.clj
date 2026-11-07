(ns metrics.cyclomatic
  "Cyclomatic complexity.

  A function's complexity is 1 plus its decision points (if, else-if, loop,
  case, catch, ternary and logical operators).  A container's complexity is the
  sum of what it declares plus the branches it opens itself.  The reader adapter
  maps Clojure forms to a flat list of decision kinds; this namespace just
  applies the count.")

(defn complexity
  "Cyclomatic complexity of a function from its decision kinds: 1 + count."
  [decision-kinds]
  (inc (count decision-kinds)))

(defn file-complexity
  "A container's complexity: the branches it opens itself (its top-level
  decision kinds) plus the complexity of every function it declares."
  [top-level-decision-kinds function-complexities]
  (+ (count top-level-decision-kinds) (reduce + 0 function-complexities)))
