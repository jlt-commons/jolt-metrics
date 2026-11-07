(ns metrics.scheme.reader
  "Read Scheme text into forms: lists with :line and :column metadata, symbols,
  strings and booleans.  This only parses tokens, so nothing in the source can run.

  Numbers and characters stay symbols.  The metrics only ask whether two are the
  same, and a symbol keeps a name with a slash, like call/cc, whole."
  (:require [metrics.scanner :as scanner]
            [metrics.scheme.scanner :as scheme-scanner]))

(def ^:private quoting-forms
  {"'" 'quote
   "`" 'quasiquote
   "," 'unquote
   ",@" 'unquote-splicing
   "#'" 'syntax
   "#`" 'quasisyntax
   "#," 'unsyntax
   "#,@" 'unsyntax-splicing})

(defn- atom-form [text]
  (case text
    ("#t" "#true") true
    ("#f" "#false") false
    (symbol nil text)))

(defn- string-form [text]
  (subs text 1 (max 1 (dec (count text)))))

(defn- list-form
  "A list with where it starts; an empty list has nothing to attach that to."
  [items t]
  (if (seq items)
    (with-meta (apply list items) {:line (:line t) :column (:col t)})
    ()))

(declare next-form)

(defn- list-items
  "The forms up to the close matching a list opened just before i, and the index
  past that close."
  [toks i opener]
  (loop [i i, items []]
    (let [t (get toks i)]
      (cond
        (nil? t) (throw (ex-info (str "unclosed list at line " (:line opener)) {:line (:line opener)}))
        (= :close (:kind t)) [items (inc i)]
        :else (let [[form j] (next-form toks i)]
                (recur j (if (= ::nothing form) items (conj items form))))))))

(defn- quoted
  "A form after a quoting prefix; comments in between don't count."
  [toks i head]
  (let [[form j] (next-form toks i)]
    (if (= ::nothing form)
      (quoted toks j head)
      [(list head form) j])))

(defn- prefixed
  "What a prefix token makes of the form after it.  Any # prefix not naming a
  quoting form opens literal data: #(1 2), #vu8(1 2)."
  [toks i t]
  (quoted toks (inc i) (get quoting-forms (:text t) 'quote)))

(defn- discarded
  "Skip the form under a #; and read as nothing."
  [toks i]
  (let [[form j] (next-form toks (inc i))]
    (if (= ::nothing form)
      (discarded toks (dec j))
      [::nothing j])))

(defn- next-form
  "The form starting at token i and the index after it; comments read as ::nothing."
  [toks i]
  (let [t (get toks i)]
    (when (nil? t)
      (throw (ex-info "unexpected end of source" {})))
    (case (:kind t)
      :comment [::nothing (inc i)]
      :discard (discarded toks i)
      :open (let [[items j] (list-items toks (inc i) t)]
              [(list-form items t) j])
      :close (throw (ex-info (str "unexpected close at line " (:line t)) {:line (:line t)}))
      :prefix (prefixed toks i t)
      :string [(string-form (:text t)) (inc i)]
      [(atom-form (:text t)) (inc i)])))

(defn read-forms
  "Read every top-level form of Scheme source into a vector."
  [source]
  (let [toks (scanner/tokens source scheme-scanner/syntax)
        n (count toks)]
    (loop [i 0, forms []]
      (if (>= i n)
        forms
        (let [[form j] (next-form toks i)]
          (recur j (if (= ::nothing form) forms (conj forms form))))))))
