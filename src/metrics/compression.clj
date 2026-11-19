(ns metrics.compression
  "Compression as a measure of information: an upper bound on Kolmogorov
  complexity, which no program can compute exactly.

  What is compressed is a token stream, not the text: metrics.tokens drops
  layout, comments and docstrings, numbers locals by where they first appear and
  makes every string one placeholder, so a size reflects the code's shape rather
  than its spelling.  Jbara and Feitelson, Quantification of code regularity
  using preprocessing and compression (2014), found that what is compressed
  matters more than the compressor; gzip, whose DEFLATE metrics.deflate runs, is
  among those they recommend.

  Two measures come of it.  A file's redundancy is the share of its token stream
  compression removes: high when the same shapes repeat in it, boilerplate or
  copies.  And two definitions are near-duplicates when their normalized
  compression distance (Li, Chen, Li, Ma and Vitanyi, The similarity metric,
  2004)

    NCD(x, y) = (C(xy) - min(C(x), C(y))) / max(C(x), C(y))

  is at most max-distance: knowing one, the other takes little more to say.
  Ishio, Maeda, Shibuya and Inoue, Cloned buggy code detection in practice using
  normalized compression distance, use the same distance over token
  streams with DEFLATE to find cloned code.

  Comparing every pair of definitions is quadratic, so two cheap tests rule most
  pairs out first: sizes too far apart to be within max-distance, since C(xy) is
  at least the larger of C(x) and C(y), and a MinHash estimate of how many token
  trigrams the two share, confirmed by the exact share before compressing.  The
  estimate comes first, so a pair that only just qualifies can be missed;
  definitions that are the same once normalized never are."
  (:require [clojure.string :as str]
            [metrics.deflate :as deflate]
            [metrics.minhash :as minhash]))

(def max-distance
  "The default NCD at or below which two definitions are near-duplicates.  Picked
  by reading the pairs found in clojure-lsp: at 0.35 they share most of their code
  and differ in a call, a keyword or a few arguments."
  0.35)

(def min-tokens
  "The default number of tokens a definition needs to be compared: below this
  compression is dominated by its own overhead, and a short definition that
  repeats is an idiom rather than a copy.  CCFinderX's default minimum clone is
  50 tokens; ours count brackets too, but not a definition's name."
  40)

(def min-shared
  "The share of token trigrams two definitions need in common, estimated and then
  exactly, before their distance is computed.  Every near-duplicate found in
  clojure-lsp shared at least 0.28 by estimate, and every one in jolt at least
  0.15 exactly; this leaves room for the estimate's error."
  0.12)

(defn token-text
  "The text a token stream is compressed as: its tokens separated by spaces,
  which no token holds."
  [tokens]
  (str/join " " tokens))

(defn size-of
  "The compressed size of a token stream."
  [tokens]
  (deflate/compressed-size (token-text tokens)))

(defn redundancy
  "The share of a token stream's text that compression removes, from 0 below 1;
  nil for no tokens.  A short text can come out larger than it went in, which
  counts as 0."
  [tokens]
  (let [text (token-text tokens)
        n (deflate/utf8-size text)]
    (when (pos? n)
      (max 0.0 (- 1.0 (/ (double (deflate/compressed-size text)) n))))))

(defn ncd
  "The normalized compression distance, given the compressed sizes of x, y and
  the two together: 0 when either says all of the other, around 1 when neither
  says anything of it; 0 when both are empty."
  [cxy cx cy]
  (let [hi (max cx cy)]
    (if (zero? hi)
      0.0
      (/ (double (- cxy (min cx cy))) hi))))

(defn distance
  "The normalized compression distance between two texts with compressed sizes
  ca and cb."
  [a ca b cb]
  (ncd (deflate/compressed-size (str a " " b)) ca cb))

;; --- near-duplicates ---------------------------------------------------------

(defn- candidate
  "A unit ready for comparing: its text, compressed size and signature."
  [u]
  (let [text (token-text (:tokens u))]
    (assoc u
           :text text
           :size (deflate/compressed-size text)
           :signature (minhash/signature (:tokens u))
           :trigrams (minhash/trigrams (:tokens u)))))

(defn within?
  "Is b inside a, as a helper sits inside the definition it was lifted out of?"
  [a b]
  (and (= (:path a) (:path b))
       (<= (:line a) (:line b))
       (<= (:end-line b) (:end-line a))))

(defn- place [u]
  (select-keys u [:path :name :line :end-line]))

(defn- pair
  "Two candidates as a near-duplicate, the earlier in the project first, or nil
  if they are more than max-d apart."
  [max-d x y]
  (when-not (or (within? x y) (within? y x))
    (let [[a b] (if (pos? (compare [(:path x) (:line x)] [(:path y) (:line y)])) [y x] [x y])
          d (distance (:text a) (:size a) (:text b) (:size b))]
      (when (<= d max-d)
        {:distance d :a (place a) :b (place b)}))))

(defn- pair-order [{:keys [distance a b]}]
  [distance (:path a) (:line a) (:path b) (:line b)])

(defn near-duplicates
  "The pairs of units at most max-d apart, closest first.  A unit is
  {:path :name :line :end-line :tokens}; units under min-n tokens are left out,
  and so is a pair where one unit lies inside the other.  Each pair is
  {:distance :a :b}, a and b being places {:path :name :line :end-line}, a the
  first in path and line order.  max-distance and min-tokens are the defaults."
  [units max-d min-n]
  (let [cs (map candidate (filter #(>= (count (:tokens %)) min-n) units))]
    (->> (minhash/similar-pairs cs max-d min-shared)
         (keep (fn [[a b]] (pair max-d a b)))
         (sort-by pair-order)
         vec)))
