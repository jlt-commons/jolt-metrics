(ns metrics.cleanup
  "Cleanup candidates: requires and functions nothing seems to use.

  Reference counting goes by name and is deliberately conservative: any symbol of
  that name anywhere counts as a use, so a candidate really has no mention in the
  analyzed files.  It can't see lookups built at runtime or callers outside them,
  so a public function listed here may still be API; a project names its API
  namespaces to keep them off the list."
  (:require [metrics.paths :as paths]
            [clojure.string :as str]))

(defn- other-references
  "How many mentions of f's name lie outside its own definition."
  [symbols f]
  (- (get symbols (:name f) 0) (:self-refs f 0)))

(defn- entry-point? [f]
  (= \- (first (:name f))))

(defn- candidates
  "The definitions of the given file that are functions or macros."
  [file private?]
  (filter #(and (contains? #{:function :macro} (:kind %))
                (= private? (boolean (:private %))))
          (:functions file)))

(defn- function-entry [file f]
  {:path (:path file) :name (:name f) :line (:line f)})

(defn unused-private
  "Private functions nothing else in their own file mentions."
  [files]
  (vec (for [file files
             f (candidates file true)
             :when (<= (other-references (:symbols file) f) 0)]
         (function-entry file f))))

(defn- namespace-pattern
  "A namespace name or glob as a regex: * stands for any run of characters, dots
  included, so a.* covers a.b and a.b.c but not a itself."
  [glob]
  (re-pattern (str/join ".*" (map #(str/replace % #"[\\.+?()\[\]{}^$|]" (fn [m] (str "\\" m)))
                                  (str/split glob #"\*" -1)))))

(defn- api-namespace?
  "Does a namespace name match one of the names or globs?"
  [globs ns-name]
  (boolean (and ns-name (some #(re-matches (namespace-pattern %) ns-name) globs))))

(defn- api-file?
  "Is a file public API: its namespace among :api-namespaces, or its path, relative
  to :root, among :api-paths?  A file with no ns form has only its path to go by."
  [{:keys [root api-namespaces api-paths]} file]
  (or (api-namespace? api-namespaces (:namespace file))
      (paths/excluded? api-paths (paths/relative root (:path file)))))

(defn unused-public
  "Public functions nothing else in the analyzed files mentions, in languages where
  that means something.  Every file's mentions count, whatever its language.  A
  file that opts call API (see api-file?) is called from outside, so it has none."
  ([files] (unused-public files nil))
  ([files opts]
   (let [symbols (apply merge-with + {} (map :symbols files))]
     (vec (for [file (filter :unused-public? files)
                :when (not (api-file? opts file))
                f (candidates file false)
                :when (and (not (entry-point? f))
                           (<= (other-references symbols f) 0))]
            (function-entry file f))))))

(defn- dead-alias
  "The alias a libspec declares, if the file refers through neither it nor the lib."
  [{:keys [qualifiers]} {:keys [lib as as-alias]}]
  (let [local (or as as-alias)]
    (when (and local
               (not (contains? qualifiers (str local)))
               (not (contains? qualifiers (str lib))))
      (str local))))

(defn- dead-refers
  "The referred names the file never mentions, or nil.  :refer :all can't be judged."
  [{:keys [symbols]} {:keys [refer only]}]
  (let [named (or refer only)]
    (when (sequential? named)
      (not-empty (vec (distinct (map str (remove #(contains? symbols (str %)) named))))))))

(defn- unused-in-libspec
  "What a libspec declares but the file never uses, or nil.  A :rename can't be
  judged, and a lib with no alias or refers is loaded for effect."
  [file spec]
  (when-not (:rename spec)
    (let [alias-name (dead-alias file spec)
          refers (dead-refers file spec)]
      (when (or alias-name refers)
        (cond-> {:path (:path file) :lib (str (:lib spec))}
          alias-name (assoc :alias alias-name)
          refers (assoc :refers refers))))))

(defn unused-requires
  "Aliases and referred names declared by a require that the file never uses."
  [files]
  (vec (for [file files
             spec (:libspecs file)
             :let [e (unused-in-libspec file spec)]
             :when e]
         e)))

(defn candidates-of
  "Every cleanup candidate of analyzed files; opts as for unused-public."
  ([files] (candidates-of files nil))
  ([files opts]
   {:unused-requires (unused-requires files)
    :unused-private (unused-private files)
    :unused-public (unused-public files opts)}))
