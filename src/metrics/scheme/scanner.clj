(ns metrics.scheme.scanner
  "What a token is in Scheme source (R6RS, Chez and Gambit): the syntax map for
  metrics.scanner.  Unlike Clojure, a comma is unquote rather than whitespace, a
  bracket is a paren, and a # can start nearly anything."
  (:require [metrics.scanner :as scanner]))

(def ^:private whitespace #{\space \tab \newline \return \formfeed})

(def ^:private directives
  "#! words that talk to the reader and are not values, unlike Gambit's #!void."
  #{"#!chezscheme" "#!r6rs" "#!fold-case" "#!no-fold-case"})

(defn- char-at [s i]
  (when (< i (count s)) (nth s i)))

(defn- block-comment-end
  "The index just past the block comment opening at i; these nest."
  [s i]
  (loop [j (+ i 2), depth 1]
    (let [c (char-at s j)
          c2 (char-at s (inc j))]
      (cond
        (or (nil? c) (zero? depth)) (min j (count s))
        (and (= c \|) (= c2 \#)) (recur (+ j 2) (dec depth))
        (and (= c \#) (= c2 \|)) (recur (+ j 2) (inc depth))
        :else (recur (inc j) depth)))))

(defn- char-literal
  "#\\x, or a named one like #\\space: at least one character, so #\\( opens nothing."
  [s i]
  [:atom (scanner/atom-end s (min (count s) (+ i 3)))])

(defn- hash-bang
  "A shebang runs to the end of the line, a directive is a comment, and any other
  #!word is an object: Gambit's #!void and Chez's #!eof sit inside expressions."
  [s i]
  (let [end (scanner/atom-end s (+ i 2))
        following (char-at s (+ i 2))]
    (cond
      (or (= \/ following) (= \space following)) (scanner/comment-token s i)
      (contains? directives (subs s i end)) [:comment end]
      :else [:atom end])))

(defn- unsyntax
  "#, or #,@"
  [s i]
  [:prefix (if (= \@ (char-at s (+ i 2))) (+ i 3) (+ i 2))])

(defn- hash-word
  "#t, #x1F, #%car, ##include: an atom, unless a list follows, as in #vu8(1 2)."
  [s i]
  (let [end (max (+ i 1) (scanner/atom-end s i))]
    (if (= \( (char-at s end))
      [:prefix end]
      [:atom end])))

(def ^:private hash-readers
  "What follows a #."
  {\| (fn [s i] [:comment (block-comment-end s i)])
   \; (fn [_ i] [:discard (+ i 2)])
   \\ char-literal
   \! hash-bang
   \( (scanner/one-char :prefix)
   \' (fn [_ i] [:prefix (+ i 2)])
   \` (fn [_ i] [:prefix (+ i 2)])
   \, unsyntax})

(defn- dispatch [s i]
  (let [reader (get hash-readers (char-at s (inc i)) hash-word)]
    (reader s i)))

(defn- unquote-token
  ", or ,@"
  [s i]
  [:prefix (if (= \@ (char-at s (inc i))) (+ i 2) (inc i))])

(defn- piped-symbol
  "|a symbol with spaces|"
  [s i]
  (let [n (count s)]
    (loop [j (inc i)]
      (cond
        (>= j n) [:atom n]
        (= \| (nth s j)) [:atom (inc j)]
        :else (recur (inc j))))))

(def ^:private readers
  (merge (zipmap [\( \[ \{] (repeat (scanner/one-char :open)))
         (zipmap [\) \] \}] (repeat (scanner/one-char :close)))
         (zipmap [\' \`] (repeat (scanner/one-char :prefix)))
         {\; scanner/comment-token
          \" scanner/string-token
          \, unquote-token
          \# dispatch
          \| piped-symbol}))

(def syntax
  "Scheme has no docstrings to find, and a bracket opens a list just as a paren does."
  {:readers readers
   :whitespace whitespace
   :doc-indexes (constantly #{})
   :list-opens #{"(" "["}})

(defn scan
  "Scan Scheme source; see metrics.scanner/scan."
  [source]
  (scanner/scan source syntax))
