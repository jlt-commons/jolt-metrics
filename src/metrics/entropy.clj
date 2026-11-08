(ns metrics.entropy
  "Unusual changes: commits that put far more information into the code, or
  take far more out, than the commits before them did.

  This follows Torres, Baltes, Treude and Wagner, Information-theoretic
  detection of unusual source code changes (Empirical Software Engineering,
  2025).  Source is read as a stream of words, identifiers split at case
  changes and at anything that isn't a letter, and a file's entropy is the
  Shannon entropy of their frequencies.  Each commit changes the code's total
  entropy by some amount; a change more than z-limit standard deviations from
  the mean of every change before it is unusual.  Keeping the whole history as
  the baseline, rather than a recent window, is what gave the paper its best
  precision: 58% of flagged commits were feature work maintainers would want to
  look at, rising to 83% counting removals and porting."
  (:require [clojure.math :as math]
            [clojure.string :as str]))

(def min-history
  "How many changes come before one gets a score: fewer say too little about
  what ordinary looks like."
  10)

(def z-limit
  "How many standard deviations from the mean a change has to be, either way,
  to be unusual; the paper's cut-off."
  3)

(defn words
  "The words of source text, lower-cased: runs of letters, split where the case
  changes, so createUser and HTTPServer are two words each.  Digits,
  punctuation and anything outside ASCII separate words."
  [source]
  (map str/lower-case (re-seq #"[A-Z]+(?![a-z])|[A-Z]?[a-z]+" source)))

(defn- log2 [x]
  (/ (math/log (double x)) (math/log 2.0)))

(defn entropy
  "The Shannon entropy, in bits, of a sequence of words' frequencies; 0 when
  there are none."
  [ws]
  (let [n (count ws)]
    (if (zero? n)
      0.0
      (- (reduce + 0.0 (map (fn [k]
                              (let [p (/ (double k) n)]
                                (* p (log2 p))))
                            (vals (frequencies ws))))))))

(defn source-entropy
  "The entropy of source text's words."
  [source]
  (entropy (words source)))

(defn- z-score
  "How many standard deviations x is from the mean of changes whose count, sum
  and sum of squared deviations are n, mean and m2; nil with too few or no
  spread."
  [n mean m2 x]
  (let [sd (if (pos? n) (math/sqrt (/ m2 n)) 0.0)]
    (when (and (>= n min-history) (pos? sd))
      (/ (- x mean) sd))))

(defn z-scores
  "Each change's z-score against the changes before it, or nil where there is
  too little history or no spread to judge by.  The running mean and spread are
  Welford's, so a long history stays accurate."
  [deltas]
  (first
   (reduce (fn [[out n mean m2] x]
             (let [n' (inc n)
                   d (- x mean)
                   mean' (+ mean (/ d n'))]
               [(conj out (z-score n mean m2 x))
                n' mean' (+ m2 (* d (- x mean')))]))
           [[] 0 0.0 0.0]
           deltas)))

(defn unusual?
  "Is a z-score past the limit, either way?"
  [z]
  (boolean (and z (> (abs z) z-limit))))

(defn unusual-changes
  "The commits whose :delta is unusual against those before them, oldest first
  in, most unusual first out, each with its :z."
  [commits]
  (->> (map (fn [c z] (assoc c :z z)) commits (z-scores (mapv :delta commits)))
       (filter #(unusual? (:z %)))
       (sort-by #(- (abs (:z %))))
       vec))
