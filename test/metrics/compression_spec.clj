(ns metrics.compression-spec
  "Contract for metrics.compression: compressed size as an upper bound on
  Kolmogorov complexity, after Li, Chen, Li, Ma and Vitanyi, The similarity
  metric (2004).

  A token stream is compressed as its tokens joined by spaces, with raw DEFLATE
  at its best level.  Its redundancy is one less its compressed size over its
  text's, never below 0, and nothing has none.  The normalized compression
  distance of x and y is (C(xy) - min(C(x), C(y))) / max(C(x), C(y)).

  Two units, definitions with their place and tokens, are near-duplicates when
  both have at least the minimum of tokens, neither lies inside the other, and
  their distance is at most the maximum; min-tokens and max-distance are the
  defaults.  Pairs come closest first, each with the
  earlier unit, by path and line, as a.  A pair the MinHash estimate rules out
  can be missed, so every pair reported is a near-duplicate, but not every
  near-duplicate need be reported; units with the same tokens always are."
  (:require [writ.spec :refer [spec ann law refine graph]]
            [clojure.string :as str]
            [metrics.deflate :as deflate]
            [metrics.compression :as c]))

(spec metrics.compression {:require :tested})

(refine Place
  [p {:path String :name String :line Nat :end-line Nat}]
  (<= (:line p) (:end-line p)))

(refine Unit
  [u {:path String :name String :line Nat :end-line Nat :tokens (Vec String)}]
  (<= (:line u) (:end-line u)))

(refine Pair
  [p {:distance Double :a Place :b Place}]
  (<= (:distance p) c/max-distance))

(ann token-text [(Vec String) -> String])
(ann size-of [(Vec String) -> Nat])
(ann redundancy [(Vec String) -> (Opt Double)])
(ann ncd [Nat Nat Nat -> Double])
(ann distance [String Nat String Nat -> Double])
(ann within? [Place Place -> Bool])
(ann near-duplicates [(Vec Unit) Double Nat -> (Vec Pair)])

(graph near-duplicates
  {:states {:tokens (Vec String), :text String, :units (Vec Unit), :pairs (Vec Pair)}
   :edges  {:tokens {[token-text] #{:text}}
            :units  {[near-duplicates Double Nat] #{:pairs}}}})

;; --- the spec's model and examples -------------------------------------------

(defn- model-ncd [cxy cx cy]
  (let [hi (max cx cy)]
    (if (zero? hi) 0.0 (/ (double (- cxy (min cx cy))) hi))))

(defn- close? [a b]
  (<= (abs (- a b)) 1e-9))

;; sixty tokens of calls, locals and keywords, and sixty of vectors of
;; characters: as alike as two different stretches of code
(def ^:private body
  (vec (mapcat (fn [i] ["(" (str "f" (mod (* i 7) 13)) (str "$" (mod i 5)) (str ":k" i) ")"])
               (range 12))))

(def ^:private other
  (vec (mapcat (fn [i] ["[" (str "g" (mod (* i 3) 11)) (str "\\c" i) "]"]) (range 15))))

(defn- unit [path line end-line tokens]
  {:path path :name (str path ":" line) :line line :end-line end-line :tokens tokens})

(defn- place [u] (dissoc u :tokens))

;; half body, half other: enough in common to be compared, too little to be a copy
(def ^:private half
  (vec (concat (subvec body 0 30) (subvec other 0 30))))

;; --- sizes -------------------------------------------------------------------

(law tokens-are-joined-by-spaces
  (= (c/token-text ["(" "inc" "$0" ")"]) "( inc $0 )"))

(law token-text-adds-one-space-between-tokens
  (forall [ts (Vec String)]
    (= (count (c/token-text ts))
       (+ (reduce + 0 (map count ts)) (max 0 (dec (count ts)))))))

(law size-is-the-compressed-size-of-the-text
  (forall [ts (Vec String)]
    (= (c/size-of ts) (deflate/compressed-size (c/token-text ts)))))

(law nothing-compresses-to-almost-nothing
  (<= (c/size-of []) 2))

(law repetition-compresses-to-a-little
  (< (c/size-of (vec (repeat 500 "x"))) 20))

;; --- redundancy ----------------------------------------------------------------

(defn- model-redundancy [ts]
  (let [text (str/join " " ts)
        n (deflate/utf8-size text)]
    (when (pos? n)
      (max 0.0 (- 1.0 (/ (double (deflate/compressed-size text)) n))))))

(law redundancy-is-the-model
  (forall [ts (Vec String)]
    (= (c/redundancy ts) (model-redundancy ts))))

(law nothing-has-no-redundancy
  (nil? (c/redundancy [])))

(law what-compression-grows-has-none
  (= 0.0 (c/redundancy ["x"])))

(law repetition-is-redundant
  (> (c/redundancy (vec (repeat 500 "x"))) 0.9))

(law repetition-is-more-redundant-than-variety
  (> (c/redundancy (vec (apply concat (repeat 5 body)))) (c/redundancy body)))

;; --- distance ------------------------------------------------------------------

(law ncd-is-the-model
  (forall [cxy Nat, cx Nat, cy Nat]
    (close? (c/ncd cxy cx cy) (model-ncd cxy cx cy))))

(law nothing-is-no-distance-from-nothing
  (= (c/ncd 0 0 0) 0.0))

(law distance-is-the-ncd-of-the-texts-together
  (let [t (c/token-text body) o (c/token-text other)
        ct (c/size-of body) co (c/size-of other)]
    (= (c/distance t ct o co)
       (model-ncd (deflate/compressed-size (str t " " o)) ct co))))

(law a-text-is-closer-to-itself-than-to-another
  (let [t (c/token-text body) o (c/token-text other)
        ct (c/size-of body) co (c/size-of other)]
    (< (c/distance t ct t ct) 0.1 (c/distance t ct o co))))

;; --- within --------------------------------------------------------------------

(law a-place-is-within-itself
  (forall [p Place]
    (c/within? p p)))

(law a-helper-is-within-its-definition
  (c/within? (place (unit "a.clj" 1 20 [])) (place (unit "a.clj" 5 9 []))))

(law a-definition-is-not-within-its-helper
  (not (c/within? (place (unit "a.clj" 5 9 [])) (place (unit "a.clj" 1 20 [])))))

(law starting-earlier-is-not-within
  (not (c/within? (place (unit "a.clj" 5 9 [])) (place (unit "a.clj" 1 9 [])))))

(law ending-later-is-not-within
  (not (c/within? (place (unit "a.clj" 1 9 [])) (place (unit "a.clj" 5 20 [])))))

(law nothing-is-within-another-file
  (not (c/within? (place (unit "a.clj" 1 20 [])) (place (unit "b.clj" 5 9 [])))))

;; --- near-duplicates -------------------------------------------------------------

(law copies-are-near-duplicates
  (let [[p & more] (c/near-duplicates [(unit "b.clj" 1 10 body) (unit "a.clj" 1 10 body)] c/max-distance c/min-tokens)]
    (and (nil? more)
         (= (place (unit "a.clj" 1 10 body)) (:a p))
         (= (place (unit "b.clj" 1 10 body)) (:b p))
         (< (:distance p) 0.1))))

(law copies-in-one-file-come-in-line-order
  (let [[p] (c/near-duplicates [(unit "a.clj" 20 30 body) (unit "a.clj" 1 10 body)] c/max-distance c/min-tokens)]
    (and (= 1 (:line (:a p))) (= 20 (:line (:b p))))))

(law a-copy-with-a-change-is-a-near-duplicate
  (= 1 (count (c/near-duplicates [(unit "a.clj" 1 10 body)
                                  (unit "b.clj" 1 10 (assoc body 10 "changed"))] c/max-distance c/min-tokens))))

(law different-code-is-not-a-near-duplicate
  (= [] (c/near-duplicates [(unit "a.clj" 1 10 body) (unit "b.clj" 1 10 other)] c/max-distance c/min-tokens)))

(law sharing-half-is-not-a-near-duplicate
  (= [] (c/near-duplicates [(unit "a.clj" 1 10 body) (unit "b.clj" 1 10 half)] c/max-distance c/min-tokens)))

(law short-copies-are-left-out
  (let [short (subvec body 0 (dec c/min-tokens))]
    (= [] (c/near-duplicates [(unit "a.clj" 1 10 short) (unit "b.clj" 1 10 short)] c/max-distance c/min-tokens))))

(law a-definition-and-its-helper-are-not-near-duplicates
  (= [] (c/near-duplicates [(unit "a.clj" 1 20 body) (unit "a.clj" 5 9 body)] c/max-distance c/min-tokens)))

(law a-tighter-distance-drops-the-changed-copy
  (= [] (c/near-duplicates [(unit "a.clj" 1 10 body)
                            (unit "b.clj" 1 10 (assoc body 10 "changed"))]
                           0.05 c/min-tokens)))

(law a-lower-minimum-takes-short-copies
  (let [short (subvec body 0 30)]
    (= 1 (count (c/near-duplicates [(unit "a.clj" 1 10 short) (unit "b.clj" 1 10 short)]
                                   c/max-distance 30)))))

(law closest-come-first
  (let [ps (c/near-duplicates [(unit "a.clj" 1 10 body)
                               (unit "b.clj" 1 10 body)
                               (unit "c.clj" 1 10 (assoc body 10 "changed"))] c/max-distance c/min-tokens)]
    (and (= 3 (count ps))
         (= (map :distance ps) (sort (map :distance ps)))
         (every? #(<= (:distance %) c/max-distance) ps))))
