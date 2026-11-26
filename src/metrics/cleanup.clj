(ns metrics.cleanup
  "Cleanup candidates: requires and functions nothing seems to use.

  Where the call graph marked a function :referenced? (Clojure, through
  jolt-lsp), that decides: only a reference resolved to the var counts, so a
  local or another namespace's var of the same name doesn't hide it.  Elsewhere
  counting goes by name and is deliberately conservative: any symbol of that
  name counts as a use.  Neither can see lookups built at runtime or callers
  outside the analyzed files, so a public function listed here may still be API;
  a project names its API namespaces to keep them off the list."
  (:require [metrics.paths :as paths]
            [clojure.string :as str]))

(defn- other-references
  "How many mentions of f's name lie outside its own definition."
  [symbols f]
  (- (get symbols (:name f) 0) (:self-refs f 0)))

(defn- unreferenced?
  "Does nothing outside f refer to it?  Resolved references when the call graph
  has them, plus mentions of its name in unindexed, the symbol counts of files
  it can't see into (Scheme, whose runtime calls Clojure names); else mentions
  of its name among symbols."
  [symbols unindexed f]
  (if (contains? f :referenced?)
    (and (not (:referenced? f)) (zero? (get unindexed (:name f) 0)))
    (<= (other-references symbols f) 0)))

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
             :when (unreferenced? (:symbols file) {} f)]
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
   (let [symbols (apply merge-with + {} (map :symbols files))
         unindexed (apply merge-with + {} (map :symbols (remove :index-analysis files)))]
     (vec (for [file (filter :unused-public? files)
                :when (not (api-file? opts file))
                f (candidates file false)
                :when (and (not (entry-point? f))
                           (unreferenced? symbols unindexed f))]
            (function-entry file f))))))

(defn- refers-in-use?
  "Does the file use a name the libspec refers?  :refer :all can't be judged, so
  it counts as use."
  [{:keys [symbols]} {:keys [refer only]}]
  (let [named (or refer only)]
    (boolean (or (= :all named)
                 (and (sequential? named) (some #(contains? symbols (str %)) named))))))

(defn- dead-alias
  "The alias a libspec declares, if the file uses the lib through none of it: not
  the alias, not the lib's own name, and none of the names it refers.  A lib in
  use through its refers isn't one to remove, and an idle alias beside them
  (`[clojure.test :as t :refer [is]]`) is too minor to list."
  [{:keys [qualifiers] :as file} {:keys [lib as as-alias] :as spec}]
  (let [local (or as as-alias)]
    (when (and local
               (not (contains? qualifiers (str local)))
               (not (contains? qualifiers (str lib)))
               (not (refers-in-use? file spec)))
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
