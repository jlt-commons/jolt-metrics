(ns metrics.tokens-test
  "Behaviour tests for the token stream compression measures, and for the host
  code under metrics.compression: DEFLATE sizes and the MinHash pair search."
  (:require [clojure.test :refer [deftest is testing]]
            [metrics.tokens :as t]
            [metrics.deflate :as deflate]
            [metrics.minhash :as minhash]
            [metrics.core :as core]))

(defn- first-function
  "The metrics of the first definition in source."
  [source]
  (first (:functions (core/analyze-source "a.clj" source))))

(deftest brackets-and-symbols-are-tokens
  (is (= ["(" "inc" "1" ")"] (t/code-tokens [] '((inc 1))))))

(deftest params-come-first-and-are-locals
  (is (= ["[" "$0" "]" "(" "inc" "$0" ")"] (t/code-tokens '[[x]] '((inc x))))))

(deftest locals-are-numbered-by-first-appearance
  (is (= (t/code-tokens '[[a b]] '((- b a)))
         (t/code-tokens '[[x y]] '((- y x))))))

(deftest let-loop-and-fn-bindings-are-locals
  (is (= ["(" "let" "[" "$0" "1" "]" "(" "fn" "[" "$1" "]" "(" "+" "$0" "$1" ")" ")" ")"]
         (t/code-tokens [] '((let [a 1] (fn [b] (+ a b))))))))

(deftest destructured-names-are-locals
  (is (= ["[" "{" ":keys" "[" "$0" "]" "}" "]" "$0"]
         (t/code-tokens '[[{:keys [a]}]] '(a)))))

(deftest a-catch-binding-is-a-local
  (is (= ["(" "try" "(" "f" ")" "(" "catch" "Exception" "$0" "$0" ")" ")"]
         (t/code-tokens [] '((try (f) (catch Exception e e)))))))

(deftest names-that-are-not-bound-stay-as-written
  (is (= ["(" "str/join" "xs" ")"] (t/code-tokens [] '((str/join xs))))))

(deftest strings-and-regexes-are-placeholders
  (is (= ["(" "f" "\"\"" "#\"\"" ")"] (t/code-tokens [] [(list 'f "some text" #"a+")]))))

(deftest literals-are-printed
  (is (= ["[" ":k" "1" "\\c" "nil" "true" "]"] (t/code-tokens [] '([:k 1 \c nil true])))))

(deftest comment-forms-are-left-out
  (is (= ["(" "f" ")"] (t/code-tokens [] '((comment (g)) (f))))))

(deftest docstrings-are-not-code
  (is (= (t/code-tokens [] '((defn f "Says what f does." [x] x)))
         (t/code-tokens [] '((defn f [x] x))))))

(deftest renamed-copies-have-the-same-tokens
  (let [a (first-function "(defn a [xs] (let [n (count xs)] (when (pos? n) (/ (reduce + xs) n))))")
        b (first-function "(defn b [vs] (let [k (count vs)] (when (pos? k) (/ (reduce + vs) k))))")]
    (is (= (:compressed-size a) (:compressed-size b)))))

;; --- deflate -------------------------------------------------------------------

(deftest utf8-size-counts-bytes
  (is (= 3 (deflate/utf8-size "abc")))
  (is (= 2 (deflate/utf8-size "é"))))

(deftest deflate-is-raw
  (is (<= (deflate/compressed-size "") 2)))

(deftest repetition-deflates-small
  (is (< (deflate/compressed-size (apply str (repeat 1000 "ab"))) 30)))

(deftest a-large-text-deflates-whole
  ;; more than the 64 KiB buffer, of text that compresses poorly
  (let [text (apply str (map #(str % ",") (range 30000)))]
    (is (< 30000 (deflate/compressed-size text) (deflate/utf8-size text)))))

;; --- minhash -------------------------------------------------------------------

(def ^:private tokens (mapv #(str "t" %) (range 60)))

(deftest equal-sets-are-estimated-equal
  (is (= 1.0 (minhash/shared-estimate (minhash/signature tokens) (minhash/signature tokens)))))

(deftest disjoint-sets-share-little
  (is (< (minhash/shared-estimate (minhash/signature tokens)
                                  (minhash/signature (mapv #(str "u" %) (range 60))))
         0.1)))

(deftest nothing-shares-nothing
  (is (= 0.0 (minhash/shared-estimate (minhash/signature []) (minhash/signature [])))))

(defn- candidate [path size ts]
  {:path path :line 1 :size size :signature (minhash/signature ts)})

(deftest similar-pairs-are-smaller-first
  (let [[[a b] & more] (minhash/similar-pairs [(candidate "b" 110 tokens) (candidate "a" 100 tokens)]
                                              0.35 0.12)]
    (is (nil? more))
    (is (= ["a" "b"] [(:path a) (:path b)]))))

(deftest sizes-far-apart-are-not-paired
  (is (= [] (minhash/similar-pairs [(candidate "a" 100 tokens) (candidate "b" 200 tokens)] 0.35 0.12))))

(deftest unrelated-signatures-are-not-paired
  (is (= [] (minhash/similar-pairs [(candidate "a" 100 tokens)
                                    (candidate "b" 100 (mapv #(str "u" %) (range 60)))]
                                   0.35 0.12))))

;; --- exact trigram overlap -------------------------------------------------------

(deftest the-exact-share-is-the-jaccard-of-trigrams
  (is (= 1.0 (minhash/shared (minhash/trigrams tokens) (minhash/trigrams tokens))))
  (is (= 0.0 (minhash/shared (minhash/trigrams tokens) (minhash/trigrams (mapv #(str "u" %) (range 60))))))
  ;; t0..t59 has 58 trigrams; t0..t29 shares 28 of them
  (is (= (/ 28.0 58) (minhash/shared (minhash/trigrams tokens) (minhash/trigrams (subvec tokens 0 30)))))
  (is (= 0.0 (minhash/shared (minhash/trigrams []) (minhash/trigrams [])))))

(deftest a-pair-needs-the-exact-share-too
  (testing "signatures that agree are not enough when the trigrams don't"
    (is (= [] (minhash/similar-pairs [(assoc (candidate "a" 100 tokens) :trigrams (minhash/trigrams tokens))
                                      (assoc (candidate "b" 100 tokens)
                                             :trigrams (minhash/trigrams (mapv #(str "u" %) (range 60))))]
                                     0.35 0.12))))
  (is (= 1 (count (minhash/similar-pairs [(assoc (candidate "a" 100 tokens) :trigrams (minhash/trigrams tokens))
                                          (assoc (candidate "b" 100 tokens) :trigrams (minhash/trigrams tokens))]
                                         0.35 0.12)))))
