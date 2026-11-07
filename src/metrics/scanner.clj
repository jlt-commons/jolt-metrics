(ns metrics.scanner
  "A small lexer for Clojure source text.  The reader discards what line counting
  needs, so this keeps it: where strings, comments and discards are, which strings
  are docstrings, and which line each list closes on.

  It works on any text, balanced or not: an unterminated string runs to the end,
  and an unmatched delimiter is simply ignored.")

;; --- tokens --------------------------------------------------------------

(def ^:private delimiter? #{\( \) \[ \] \{ \} \" \;})
(def ^:private whitespace? #{\space \tab \newline \return \formfeed \,})
(def ^:private prefix? #{\' \` \~ \@})

(defn- string-end
  "The index just past the string whose opening quote is at i."
  [s i]
  (let [n (count s)]
    (loop [j (inc i)]
      (cond
        (>= j n) n
        (= \\ (nth s j)) (recur (+ j 2))
        (= \" (nth s j)) (inc j)
        :else (recur (inc j))))))

(defn- line-end
  "The index of the newline ending the line that holds i, or the end of s."
  [s i]
  (let [n (count s)]
    (loop [j i]
      (if (or (>= j n) (= \newline (nth s j)))
        j
        (recur (inc j))))))

(defn- atom-end
  "The index just past the atom starting at i."
  [s i]
  (let [n (count s)]
    (loop [j i]
      (if (or (>= j n)
              (delimiter? (nth s j))
              (whitespace? (nth s j)))
        j
        (recur (inc j))))))

(defn- token-at
  "The next token at i, as [kind end], where end is the index just past it."
  [s i]
  (let [n (count s)
        c (nth s i)
        c2 (when (< (inc i) n) (nth s (inc i)))]
    (cond
      (= c \;) [:comment (line-end s i)]
      (= c \") [:string (string-end s i)]
      (= c \\) [:atom (atom-end s (min n (+ i 2)))]
      (#{\( \[ \{} c) [:open (inc i)]
      (#{\) \] \}} c) [:close (inc i)]
      (= c \^) [:meta (inc i)]
      (prefix? c) [:prefix (inc i)]
      (and (= c \#) (= c2 \_)) [:discard (+ i 2)]
      (and (= c \#) (= c2 \")) [:atom (string-end s (inc i))]
      (and (= c \#) (= c2 \!)) [:comment (line-end s i)]
      (and (= c \#) (= c2 \^)) [:meta (+ i 2)]
      (= c \#) [:prefix (inc i)]
      :else [:atom (max (inc i) (atom-end s i))])))

(defn- tokens
  "The tokens of s, each {:kind :text :line :col :end-line}."
  [s]
  (let [n (count s)]
    (loop [i 0, line 1, col 1, out []]
      (if (>= i n)
        out
        (let [c (nth s i)]
          (cond
            (= c \newline) (recur (inc i) (inc line) 1 out)
            (whitespace? c) (recur (inc i) line (inc col) out)
            :else
            (let [[kind end] (token-at s i)
                  text (subs s i (min end n))
                  breaks (count (filter #(= \newline %) text))
                  end-line (+ line breaks)
                  next-col (if (pos? breaks)
                             (- (count text) (.lastIndexOf text "\n"))
                             (+ col (count text)))]
              (recur end end-line next-col
                     (conj out {:kind kind :text text :line line :col col
                                :end-line end-line})))))))))

;; --- structure -----------------------------------------------------------

(defn- matching-closes
  "A map from the index of each open delimiter to its close's index."
  [toks]
  (first
   (reduce (fn [[matches stack] [i t]]
             (case (:kind t)
               :open [matches (conj stack i)]
               :close (if (seq stack)
                        [(assoc matches (peek stack) i) (pop stack)]
                        [matches stack])
               [matches stack]))
           [{} []]
           (map-indexed vector toks))))

(defn- form-end
  "The index of the last token of the form starting at token i."
  [toks matches i]
  (let [last-i (dec (count toks))
        kind (:kind (get toks i))]
    (cond
      (> i last-i) last-i
      (= :open kind) (get matches i last-i)
      (= :prefix kind) (form-end toks matches (inc i))
      (or (= :meta kind) (= :discard kind))
      (form-end toks matches (inc (form-end toks matches (inc i))))
      :else i)))

(def ^:private documented-forms '#{"defn" "defn-" "defmacro" "defmulti" "ns"})
(def ^:private value-forms '#{"def" "defonce"})

(defn- after-metadata
  "The index of the first token at or after i that is not metadata."
  [toks matches i]
  (if (= :meta (:kind (get toks i)))
    (recur toks matches (inc (form-end toks matches (inc i))))
    i))

(defn- docstring-index
  "The index of the docstring of the definition opening at i, or nil.  In a def
  the string must be followed by a value, or it is the value."
  [toks matches i]
  (let [head (:text (get toks (inc i)))
        name-i (after-metadata toks matches (+ i 2))
        doc-i (inc name-i)
        follower (:kind (get toks (inc doc-i)))]
    (when (= :string (:kind (get toks doc-i)))
      (cond
        (contains? documented-forms head) doc-i
        (and (contains? value-forms head) (not= :close follower)) doc-i
        :else nil))))

(defn- doc-indexes
  "The indexes of every token that is documentation."
  [toks matches]
  (set
   (mapcat
    (fn [[i t]]
      (when (and (= :open (:kind t)) (= "(" (:text t)))
        (if (= "defprotocol" (:text (get toks (inc i))))
          (filter #(= :string (:kind (get toks %)))
                  (range i (inc (get matches i i))))
          (when-let [d (docstring-index toks matches i)] [d]))))
    (map-indexed vector toks))))

(defn- discarded-indexes
  "The indexes of every token under a #_ discard."
  [toks matches]
  (set
   (mapcat
    (fn [[i t]]
      (when (= :discard (:kind t))
        (range i (inc (form-end toks matches (inc i))))))
    (map-indexed vector toks))))

;; --- the result ----------------------------------------------------------

(defn- line-count
  "Lines of text: a final newline ends a line rather than starting one."
  [s]
  (let [breaks (count (filter #(= \newline %) s))]
    (if (or (empty? s) (= \newline (last s)))
      breaks
      (inc breaks))))

(def ^:private rank {:blank 0 :comment 1 :code 2})

(defn- mark
  "Raise the class of every line a token spans; code outranks comment outranks blank."
  [classes t class-name]
  (reduce (fn [cs line]
            (let [idx (dec line)]
              (if (and (< idx (count cs))
                       (> (rank class-name) (rank (nth cs idx))))
                (assoc cs idx class-name)
                cs)))
          classes
          (range (:line t) (inc (:end-line t)))))

(defn scan
  "Scan source into {:classes [per-line :blank|:comment|:code]
                     :extents {[line col] end-line of the list opening there}}."
  [source]
  (let [toks (tokens source)
        matches (matching-closes toks)
        docs (doc-indexes toks matches)
        discarded (discarded-indexes toks matches)
        classes (reduce
                 (fn [cs [i t]]
                   (mark cs t (if (or (= :comment (:kind t))
                                      (contains? docs i)
                                      (contains? discarded i))
                                :comment
                                :code)))
                 (vec (repeat (line-count source) :blank))
                 (map-indexed vector toks))
        extents (into {}
                      (for [[open close] matches
                            :let [t (get toks open)]
                            :when (= "(" (:text t))]
                        [[(:line t) (:col t)] (:end-line (get toks close))]))]
    {:classes classes :extents extents}))

(defn line-classes
  "The class of each line of source: :blank, :comment or :code."
  [source]
  (:classes (scan source)))
