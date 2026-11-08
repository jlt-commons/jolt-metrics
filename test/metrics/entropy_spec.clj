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
  is unusual.

  Over files rather than time: a file's surprisal is the mean bits per word of
  its words under the word distribution of the other files, smoothed by adding
  one to every count in the project's vocabulary.  Files with fewer than
  min-words words get none.  A file whose surprisal is more than z-limit
  standard deviations above the mean of the files' is unusual."
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

(refine Scored
  [f {:path String :surprisal (Opt Double)}]
  true)

(refine UnusualFile
  [f {:path String :surprisal (Opt Double) :z Double}]
  true)

(ann words [String -> (List String)])
(ann word-counts [String -> (Map String Nat)])
(refine Surprisals
  [xs (Vec (Opt Double))]
  (every? #(or (nil? %) (<= 0.0 %)) xs))

(ann surprisals [(Vec (Map String Nat)) -> Surprisals])
(ann spread-z-scores [(Vec (Opt Double)) -> (Vec (Opt Double))])
(ann unusual-files [(Vec Scored) -> (Vec UnusualFile)])
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

(graph unusual-files
  {:states {:source String, :counts (Map String Nat), :all-counts (Vec (Map String Nat)),
            :surprisals Surprisals, :spread (Vec (Opt Double)),
            :files (Vec Scored), :unusual (Vec UnusualFile)}
   :edges  {:source     {[word-counts] #{:counts}}
            :all-counts {[surprisals] #{:surprisals}}
            :surprisals {[spread-z-scores] #{:spread}}
            :files      {[unusual-files] #{:unusual}}}})

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

;; --- over files ------------------------------------------------------------

(law word-counts-count-the-words
  (= (e/word-counts "fooBar foo") {"foo" 2 "bar" 1}))

(law word-counts-are-the-frequencies-of-the-words
  (forall [s String]
    (= (e/word-counts s) (frequencies (e/words s)))))

(defn- model-surprisals [counts]
  (let [total (apply merge-with + {} counts)
        n-all (reduce + 0 (vals total))
        v (count total)]
    (vec (for [c counts
               :let [n (reduce + 0 (vals c))]]
           (when (>= n e/min-words)
             (/ (reduce + 0.0 (for [[w k] c]
                                (* k (- (log2 (/ (+ 1.0 (- (get total w 0) k))
                                                 (+ (- n-all n) v)))))))
                n))))))

(law surprisals-are-the-model
  (forall [counts (Vec (Map String Nat))]
    (every? true? (map close? (e/surprisals counts) (model-surprisals counts)))))

(law there-is-a-surprisal-per-file
  (forall [counts (Vec (Map String Nat))]
    (= (count (e/surprisals counts)) (count counts))))

(law small-files-have-no-surprisal
  (let [[small big] (e/surprisals [{"a" (dec e/min-words)} {"a" 100}])]
    (and (nil? small) (some? big))))

(law surprisal-is-never-negative
  (forall [counts (Vec (Map String Nat))]
    (every? #(or (nil? %) (<= 0.0 %)) (e/surprisals counts))))

;; two files that share their words and one that shares none: the stranger is
;; the more surprising
(def ^:private shared {"get" 30 "user" 30})

(law a-file-of-foreign-words-is-more-surprising
  (let [[a b c] (e/surprisals [shared shared {"zap" 30 "frob" 30}])]
    (and (= a b) (< a c))))

(law spread-scores-are-against-every-file
  (= (e/spread-z-scores (conj (vec (take 10 (cycle [1.0 3.0]))) nil 6.0))
     (let [xs (conj (vec (take 10 (cycle [1.0 3.0]))) 6.0)
           n (count xs)
           mean (/ (reduce + xs) n)
           sd (math/sqrt (/ (reduce + (map #(let [d (- % mean)] (* d d)) xs)) n))]
       (conj (mapv #(/ (- % mean) sd) (butlast xs)) nil (/ (- 6.0 mean) sd)))))

(law identical-files-have-no-spread-score
  (every? nil? (e/spread-z-scores (vec (repeat 12 5.0)))))

(law too-few-files-have-no-spread-score
  (every? nil? (e/spread-z-scores (vec (take (dec e/min-history) (cycle [1.0 9.0]))))))

(law there-is-a-spread-score-per-file
  (forall [xs (Vec (Opt Int))]
    (= (count xs) (count (e/spread-z-scores (mapv #(some-> % double) xs))))))

(defn- scored [i s] {:path (str "f" i ".clj") :surprisal s})

(def ^:private typical (vec (take 20 (cycle [9.0 10.0]))))

(law an-unusually-surprising-file-is-unusual
  (let [files (conj (mapv scored (range) typical) (scored 20 20.0) (scored 21 nil))
        [u & more] (e/unusual-files files)]
    (and (nil? more) (= "f20.clj" (:path u)) (> (:z u) e/z-limit))))

(law an-unusually-ordinary-file-is-not-unusual
  (= [] (e/unusual-files (conj (mapv scored (range) typical) (scored 20 0.0)))))

(law unusual-files-come-most-surprising-first
  (forall [xs (Vec Int)]
    (let [us (e/unusual-files (mapv scored (range) (map double xs)))]
      (and (= (map :z us) (sort-by - (map :z us)))
           (every? #(> (:z %) e/z-limit) us)))))
