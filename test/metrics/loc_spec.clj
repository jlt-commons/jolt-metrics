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
(ann tally [(List Keyword) -> Counts])

(graph loc
  {:states {:source String, :classes (List Keyword), :counts Counts}
   :edges  {:source {[count-lines] #{:counts}}
            :classes {[tally] #{:counts}}}})

;; --- tallying line classes ----------------------------------------------

(law tally-counts-each-class
  (= (loc/tally [:code :comment :blank :code])
     {:loc 4 :sloc 2 :cloc 1 :blank 1}))

(law tally-counts-every-line
  {:require :tested :because "reduce over update is outside the prover's model"}
  (forall [cs (List Keyword)]
    (= (:loc (loc/tally cs)) (count cs))))

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

;; --- line counting follows the text, not split-lines -----------------------

(law an-empty-source-has-no-lines
  (= (loc/count-lines "") {:loc 0 :sloc 0 :cloc 0 :blank 0}))

(law trailing-blank-lines-count
  (= (loc/count-lines "a\n\n\n") {:loc 3 :sloc 1 :cloc 0 :blank 2}))

;; --- strings, docstrings and discards ---------------------------------------

(law a-string-is-source-whatever-it-holds
  (= (loc/count-lines "(def x \"a\n;; not comment\n\nb\")")
     {:loc 4 :sloc 4 :cloc 0 :blank 0}))

(law docstring-lines-are-comments
  (= (loc/count-lines "(defn f\n  \"doc\n  more doc\"\n  [])")
     {:loc 4 :sloc 2 :cloc 2 :blank 0}))

(law a-docstring-sharing-its-line-with-code-is-source
  (= (loc/count-lines "(defn f \"doc\"\n  [])")
     {:loc 2 :sloc 2 :cloc 0 :blank 0}))

(law a-def-value-is-not-a-docstring
  (= (loc/count-lines "(def x\n  \"value\")")
     {:loc 2 :sloc 2 :cloc 0 :blank 0}))

(law metadata-does-not-hide-a-docstring
  (= (loc/count-lines "(defn ^{:a 1} ^:b f\n  \"doc\"\n  [])")
     {:loc 3 :sloc 2 :cloc 1 :blank 0}))

(law ns-and-protocol-docs-are-comments
  (= (loc/count-lines "(ns a\n  \"doc\"\n  (:b))\n(defprotocol P\n  \"doc\"\n  (m [x]\n    \"mdoc\"\n    ))")
     {:loc 8 :sloc 5 :cloc 3 :blank 0}))

(law discarded-forms-are-comments
  (= (loc/count-lines "#_(a\n b)\n(c)")
     {:loc 3 :sloc 1 :cloc 2 :blank 0}))

(law a-char-literal-opens-nothing
  (= (:sloc (loc/count-lines "(f \\; \\\")\n(g)")) 2))

(law a-regex-holds-its-delimiters
  (= (:sloc (loc/count-lines "(f #\"a;\\\"b\")\n(g)")) 2))

(law a-shebang-is-a-comment
  (= (:cloc (loc/count-lines "#!/usr/bin/env jolt\n(f)")) 1))

(law lines-add-up
  {:require :tested
   :because "string splitting and trimming are outside the prover's model"}
  (forall [src String]
    (= (:loc (loc/count-lines src))
       (+ (:sloc (loc/count-lines src))
          (:cloc (loc/count-lines src))
          (:blank (loc/count-lines src))))))
