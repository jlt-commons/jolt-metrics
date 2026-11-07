(ns metrics.loc-spec
  "Contract for metrics.loc: line-based LOC for a language with only line
  comments (Clojure).

  A comment line is one whose first non-blank character is `;`; sloc is every
  other non-blank line."
  (:require [writ.spec :refer [spec ann law refine graph]]
            [clojure.string :as str]
            [metrics.loc :as loc]))

(spec metrics.loc {:require :proved})

(refine Counts
  [m {:loc Nat, :sloc Nat, :cloc Nat, :blank Nat}]
  true)

(ann count-lines [String -> Counts])

(graph loc
  {:states {:source String, :counts Counts}
   :edges  {:source {[count-lines] #{:counts}}}})

;; --- worked examples -----------------------------------------------------

(law three-lines
  (= (:loc (loc/count-lines "a\nb\nc")) 3))

(law all-source-lines
  (= (:sloc (loc/count-lines "a\nb")) 2))

(law a-comment-line
  (= (:cloc (loc/count-lines "; hi\n")) 1))

(law an-indented-comment-line
  (= (:cloc (loc/count-lines "  ; indented")) 1))

(law a-blank-line
  (= (:blank (loc/count-lines "a\n\nb")) 1))

(law code-with-trailing-comment-is-source
  (= (:sloc (loc/count-lines "a ; trailing")) 1))

(law lines-add-up
  {:require :tested
   :because "string splitting and trimming are outside the prover's model"}
  (forall [src String]
    (= (:loc (loc/count-lines src))
       (+ (:sloc (loc/count-lines src))
          (:cloc (loc/count-lines src))
          (:blank (loc/count-lines src))))))
