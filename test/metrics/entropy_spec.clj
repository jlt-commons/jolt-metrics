(ns metrics.entropy-spec
  "Contract for metrics.entropy: unusual changes, after Torres, Baltes, Treude
  and Wagner, Information-theoretic detection of unusual source code changes
  (Empirical Software Engineering, 2025).

  Source text is a stream of words: identifiers split at case changes and at
  anything that isn't a letter, lower-cased.  A file's entropy is the Shannon
  entropy of its word frequencies, in bits.  A commit's change is the sum of
  its files' entropy changes, and its z-score is how many standard deviations
  that change sits from the mean of the commits before it.  A commit with at
  least min-history commits before it and a z-score over z-limit either way
  is unusual."
  (:require [writ.spec :refer [spec ann law refine graph assume]]
            [clojure.math :as math]
            [clojure.string :as str]
            [metrics.entropy :as e]))

(spec metrics.entropy {:require :tested})

(assume math/log [Double -> Double])
(assume math/sqrt [Double -> Double])
(assume str/lower-case [String -> String])

(refine Commit
  [c {:sha String :date String :subject String :delta Double :files (Vec String)}]
  true)

(refine Unusual
  [c {:sha String :date String :subject String :delta Double :files (Vec String)
      :z Double}]
  true)

(ann words [String -> (List String)])
(ann entropy [(List String) -> Double])
(ann source-entropy [String -> Double])
(ann z-scores [(Vec Double) -> (Vec (Opt Double))])
(ann unusual? [(Opt Double) -> Bool])
(ann unusual-changes [(Vec Commit) -> (Vec Unusual)])

(graph unusual-changes
  {:states {:source String, :words (List String), :entropy Double,
            :commits (Vec Commit), :deltas (Vec Double), :scores (Vec (Opt Double)),
            :unusual (Vec Unusual)}
   :edges  {:source  {[words] #{:words}, [source-entropy] #{:entropy}}
            :words   {[entropy] #{:entropy}}
            :deltas  {[z-scores] #{:scores}}
            :commits {[unusual-changes] #{:unusual}}}})

;; --- the spec's model ------------------------------------------------------

(defn- log2 [x] (/ (math/log (double x)) (math/log 2.0)))

(defn- model-entropy [ws]
  (let [n (count ws)]
    (if (zero? n)
      0.0
      (- (reduce + 0.0 (for [[_ k] (frequencies ws)
                             :let [p (/ (double k) n)]]
                         (* p (log2 p))))))))

(defn- model-z [history x]
  (let [n (count history)]
    (when (>= n e/min-history)
      (let [mean (/ (reduce + 0.0 history) n)
            sd (math/sqrt (/ (reduce + 0.0 (map #(let [d (- % mean)] (* d d)) history)) n))]
        (when (pos? sd)
          (/ (- x mean) sd))))))

(defn- model-scores [xs]
  (vec (for [i (range (count xs))]
         (model-z (subvec xs 0 i) (nth xs i)))))

(defn- close? [a b]
  (or (and (nil? a) (nil? b))
      (and (some? a) (some? b)
           (<= (abs (- a b)) (* 1e-6 (max 1.0 (abs b)))))))

(defn- commit [delta]
  {:sha "c" :date "2025-01-01" :subject "s" :delta (double delta) :files ["a.clj"]})

;; --- words -----------------------------------------------------------------

(law identifiers-split-into-words
  (= (e/words "(defn createUser [HTTPServer x2] ;; parse-log snake_case")
     ["defn" "create" "user" "http" "server" "x" "parse" "log" "snake" "case"]))

(law words-are-lower-case-letters
  (forall [s String]
    (every? #(re-matches #"[a-z]+" %) (e/words s))))

(law punctuation-and-numbers-are-not-words
  (= (e/words "(+ 1 2) [] {} 3.14 #_ ;") []))

;; --- entropy ---------------------------------------------------------------

(law nothing-has-no-entropy (= (e/entropy []) 0.0))
(law one-word-repeated-has-no-entropy (= (e/entropy ["a" "a" "a"]) 0.0))
(law two-equally-likely-words-are-one-bit (= (e/entropy ["a" "b"]) 1.0))
(law four-equally-likely-words-are-two-bits (= (e/entropy ["a" "b" "c" "d"]) 2.0))
(law a-70-30-split
  (= (int (* 1000 (e/entropy ["a" "a" "a" "a" "a" "a" "a" "b" "b" "b"]))) 881))

(law entropy-is-the-model
  (forall [ws (List String)]
    (close? (e/entropy ws) (model-entropy ws))))

(law entropy-is-at-most-the-log-of-the-vocabulary
  (forall [ws (List String)]
    (<= (e/entropy ws) (+ 1e-9 (if (seq ws) (log2 (count (distinct ws))) 0.0)))))

(law source-entropy-is-the-entropy-of-its-words
  (forall [s String]
    (= (e/source-entropy s) (e/entropy (e/words s)))))

;; --- z-scores --------------------------------------------------------------

(law there-is-a-score-per-change
  (forall [xs (Vec Int)]
    (= (count (e/z-scores (mapv double xs))) (count xs))))

(law too-little-history-has-no-score
  (forall [xs (Vec Int)]
    (every? nil? (take e/min-history (e/z-scores (mapv double xs))))))

(law scores-are-the-model
  (forall [xs (Vec Int)]
    (let [ds (mapv double xs)]
      (every? true? (map close? (e/z-scores ds) (model-scores ds))))))

(law no-spread-has-no-score
  (every? nil? (e/z-scores (vec (repeat 20 5.0)))))

;; ten changes alternating 1 and 3 have mean 2 and deviation 1, so 6 is four
;; deviations up and -2 four down
(def ^:private steady (vec (take 10 (cycle [1.0 3.0]))))

(law a-spike-scores-against-the-changes-before-it
  (= (last (e/z-scores (conj steady 6.0))) 4.0))

(law a-drop-scores-negative
  (= (last (e/z-scores (conj steady -2.0))) -4.0))

;; --- unusual ---------------------------------------------------------------

(law over-the-limit-either-way-is-unusual
  (and (e/unusual? 3.5) (e/unusual? -3.5)
       (not (e/unusual? 3.0)) (not (e/unusual? -1.0)) (not (e/unusual? nil))))

(law a-spike-is-an-unusual-change
  (= (e/unusual-changes (mapv commit (conj steady 6.0)))
     [(assoc (commit 6.0) :z 4.0)]))

(law ordinary-history-has-no-unusual-changes
  (= (e/unusual-changes (mapv commit steady)) []))

(law the-most-unusual-come-first
  (= (map :z (e/unusual-changes (mapv commit (conj steady -2.0 100.0))))
     (sort-by #(- (abs %)) (keep #(when (e/unusual? %) %)
                                (e/z-scores (conj steady -2.0 100.0))))))

(law unusual-changes-are-the-unusual-scores
  (forall [xs (Vec Int)]
    (let [cs (mapv commit xs)]
      (= (count (e/unusual-changes cs))
         (count (filter e/unusual? (e/z-scores (mapv :delta cs))))))))
