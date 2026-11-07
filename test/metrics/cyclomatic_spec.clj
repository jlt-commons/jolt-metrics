(ns metrics.cyclomatic-spec
  "Contract for metrics.cyclomatic: cyclomatic complexity.

  A function's complexity is 1 plus its decision points; a container's is the
  sum of the branches it opens itself and the complexity of what it declares."
  (:require [writ.spec :refer [spec ann law refine graph]]
            [metrics.cyclomatic :as cyc]))

(spec metrics.cyclomatic {:require :proved})

(ann complexity      [(List Keyword) -> Nat])
(ann file-complexity [(List Keyword) (List Nat) -> Nat])

(graph cyclomatic
  {:states {:decisions (List Keyword), :complexities (List Nat), :ccn Nat}
   :edges  {:decisions {[complexity] #{:ccn} [file-complexity (List Nat)] #{:ccn}}}})

(law complexity-is-one-plus-decisions
  (forall [kinds (List Keyword)]
    (= (cyc/complexity kinds) (inc (count kinds)))))

(law a-function-without-decisions-is-one
  (= (cyc/complexity []) 1))

(law three-decisions-is-four
  (= (cyc/complexity [:if :loop :and]) 4))

(law a-file-sums-its-parts
  (forall [top (List Keyword), fns (List Nat)]
    (= (cyc/file-complexity top fns)
       (+ (count top) (reduce + 0 fns)))))

(law an-empty-file-is-zero
  (= (cyc/file-complexity [] []) 0))

(law a-file-example
  (= (cyc/file-complexity [:if] [1 2 3]) 7))
