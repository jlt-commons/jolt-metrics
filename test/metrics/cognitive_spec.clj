(ns metrics.cognitive-spec
  "Contract for metrics.cognitive: cognitive complexity over a fixed-branching
  tagged tree.

  A node is :Leaf, :Seq (head tail), :If (test then else), :Else (inner),
  :Nest (body), :Flat (body) or :Deep (body).  :If and :Nest add 1 plus their
  nesting depth; an else-if adds a flat 1; :Flat adds a flat 1; :Deep adds nothing
  but raises the depth of what it holds."
  (:require [writ.spec :refer [spec data ann law refine graph]]
            [metrics.cognitive :as cog]))

(spec metrics.cognitive {:require :proved})

(data Node
  (Leaf)
  (Seq Node Node)
  (If Node Node Node)
  (Else Node)
  (Nest Node)
  (Flat Node)
  (Deep Node))

(ann complexity    [Node -> Nat])
(ann complexity-at [Node Nat -> Nat])
(ann max-nesting   [Node -> Nat])
(ann nesting-at    [Node Nat -> Nat])

(graph cognitive
  {:states {:node Node, :score Nat}
   :edges  {:node {[complexity] #{:score} [complexity-at Nat] #{:score}
                  [max-nesting] #{:score} [nesting-at Nat] #{:score}}}})

;; --- the spec's model: the rules, recomputed -----------------------------

(defn model-at [node depth]
  (case (first node)
    :Leaf 0
    :Seq (let [[_ head tail] node]
           (+ (model-at head depth) (model-at tail depth)))
    :If (let [[_ test then else-node] node]
          (+ 1 depth
             (model-at test (inc depth))
             (model-at then (inc depth))
             (model-at else-node depth)))
    :Else (let [[_ inner] node]
            (if (= :If (first inner))
              (let [[_ test then else-node] inner]
                (+ 1
                   (model-at test (inc depth))
                   (model-at then (inc depth))
                   (model-at else-node depth)))
              (model-at inner (inc depth))))
    :Nest (let [[_ body] node]
            (+ 1 depth (model-at body (inc depth))))
    :Flat (let [[_ body] node]
            (+ 1 (model-at body depth)))
    :Deep (let [[_ body] node]
            (model-at body (inc depth)))))

(defn model [node] (model-at node 0))

;; --- the score follows the rules -----------------------------------------

(law the-score-is-the-model
  {:require :tested
   :because "the prover gets stuck inducting over the :Else case"}
  (forall [n Node]
    (= (cog/complexity n) (model n))))

;; --- worked examples ------------------------------------------------------

(law a-leaf-is-zero
  (= (cog/complexity [:Leaf]) 0))

(law a-bare-if-is-one
  (= (cog/complexity [:If [:Leaf] [:Leaf] [:Leaf]]) 1))

(law a-nest-is-one
  (= (cog/complexity [:Nest [:Leaf]]) 1))

(law a-nested-if-adds-nesting
  (= (cog/complexity [:Nest [:Seq [:If [:Leaf] [:Leaf] [:Leaf]] [:Leaf]]]) 3))

(law an-else-if-adds-a-flat-one
  (= (cog/complexity
      [:If [:Leaf] [:Leaf] [:Else [:If [:Leaf] [:Leaf] [:Leaf]]]])
     2))

(law a-nested-else-if-still-adds-a-flat-one
  (= (cog/complexity
      [:Nest [:If [:Leaf] [:Leaf] [:Else [:If [:Leaf] [:Leaf] [:Leaf]]]]])
     4))

(law flat-adds-one-at-any-depth
  (= (cog/complexity [:Nest [:Flat [:Leaf]]]) 2))

(law deep-adds-nothing-itself
  (= (cog/complexity [:Deep [:Leaf]]) 0))

(law deep-raises-the-depth-it-holds
  (= (cog/complexity [:Deep [:If [:Leaf] [:Leaf] [:Leaf]]]) 2))

;; --- nesting -------------------------------------------------------------

(law nothing-to-nest-is-zero
  (and (= (cog/max-nesting [:Leaf]) 0)
       (= (cog/max-nesting [:Deep [:Leaf]]) 0)
       (= (cog/max-nesting [:Flat [:Leaf]]) 0)))

(law one-structure-is-one
  (and (= (cog/max-nesting [:If [:Leaf] [:Leaf] [:Leaf]]) 1)
       (= (cog/max-nesting [:Nest [:Leaf]]) 1)))

(law nesting-counts-the-deepest-path
  (= (cog/max-nesting [:Seq [:Nest [:Nest [:Leaf]]] [:Nest [:Leaf]]]) 2))

(law an-else-if-chain-stays-at-one
  (= (cog/max-nesting
      [:If [:Leaf] [:Leaf] [:Else [:If [:Leaf] [:Leaf] [:Else [:If [:Leaf] [:Leaf] [:Leaf]]]]]])
     1))

(law a-plain-else-nests
  (= (cog/max-nesting [:If [:Leaf] [:Leaf] [:Else [:Nest [:Leaf]]]]) 2))

(law a-closure-counts-toward-nesting
  (= (cog/max-nesting [:Deep [:If [:Leaf] [:Leaf] [:Leaf]]]) 2))

(law a-plain-else-nests-its-content
  (= (cog/complexity
      [:If [:Leaf] [:Leaf] [:Else [:Nest [:Leaf]]]])
     3))

(law a-seq-descends-without-increment
  (= (cog/complexity [:Seq [:Nest [:Leaf]] [:Leaf]]) 1))
