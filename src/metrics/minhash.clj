(ns metrics.minhash
  "Which pairs of token streams are worth comparing closely: a cheap estimate of
  how many token trigrams two share, so the quadratic part of near-duplicate
  search touches numbers rather than text.

  One-permutation MinHash: each trigram's hash picks one of signature-size
  buckets, which keeps the least hash it gets.  Two signatures agree in a bucket
  with the probability that the sets agree there, so the share of agreeing
  buckets estimates their Jaccard similarity.  Sets that are equal always agree
  in every bucket.

  Arrays and primitive loops, for speed: this is the hot path.")

(def ^:private ^:const signature-size
  "Buckets in a signature: enough to tell a pair sharing a quarter of its
  trigrams from one sharing almost none, and few enough to compare quickly."
  32)

(def ^:private ^:const empty-bucket Long/MAX_VALUE)

(defn- mix
  "A 64-bit hash of h, its bits mixed so that every bucket is as likely."
  ^long [^long h]
  (let [x (unchecked-multiply (bit-xor h 2654435761) -7046029254386353131)
        x (bit-xor x (unsigned-bit-shift-right x 29))
        x (unchecked-multiply x -4658895280553007687)]
    (bit-xor x (unsigned-bit-shift-right x 32))))

(defn signature
  "The MinHash signature of a token stream's trigrams."
  [tokens]
  (let [sig (long-array signature-size empty-bucket)]
    (doseq [g (partition 3 1 tokens)]
      (let [m (mix (hash g))
            k (int (bit-and m (dec signature-size)))]
        (when (< m (aget sig k))
          (aset sig k m))))
    sig))

(defn shared-estimate
  "The estimated Jaccard similarity of two signatures' sets: the share of buckets
  they agree on, of those not empty in both; 0 when both are empty."
  ^double [^longs a ^longs b]
  (loop [k 0 same 0 empty 0]
    (if (< k signature-size)
      (let [x (aget a k) y (aget b k)]
        (cond
          (not (== x y)) (recur (inc k) same empty)
          (== x empty-bucket) (recur (inc k) same (inc empty))
          :else (recur (inc k) (inc same) empty)))
      (if (== empty signature-size)
        0.0
        (/ (double same) (- signature-size empty))))))

(defn similar-pairs
  "The pairs [a b] of candidates {:size :signature :path :line}, a no larger than
  b, whose sizes are within max-gap of the larger, as a share of it, and whose
  signatures share at least min-shared.  The candidates are sorted by size, so
  once one is too far from a every later one is too."
  [candidates max-gap min-shared]
  (let [cs (vec (sort-by (juxt :size :path :line) candidates))
        n (count cs)
        sizes (long-array (map :size cs))
        sigs (object-array (map :signature cs))]
    (loop [i 0 j 1 acc (transient [])]
      (cond
        (>= i n) (persistent! acc)
        (or (>= j n)
            (> (- (aget sizes j) (aget sizes i)) (* max-gap (aget sizes j))))
        (recur (inc i) (+ i 2) acc)
        :else
        (recur i (inc j)
               (if (>= (shared-estimate (aget sigs i) (aget sigs j)) min-shared)
                 (conj! acc [(nth cs i) (nth cs j)])
                 acc))))))
