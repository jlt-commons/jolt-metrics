(ns metrics.cleanup
  "Cleanup candidates: requires and functions nothing seems to use.

  Reference counting goes by name and is deliberately conservative: any symbol of
  that name anywhere counts as a use, so a candidate really has no mention in the
  analyzed files.  It can't see lookups built at runtime or callers outside them,
  so a public function listed here may still be API.")

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

(defn unused-public
  "Public functions nothing else in the analyzed files mentions."
  [files]
  (let [symbols (apply merge-with + {} (map :symbols files))]
    (vec (for [file files
               f (candidates file false)
               :when (and (not (entry-point? f))
                          (<= (other-references symbols f) 0))]
           (function-entry file f)))))

(defn- unused-in-libspec
  "What a libspec declares but the file never uses, or nil.  A :rename or :refer
  :all can't be judged, and a lib with no alias or refers is loaded for effect."
  [file {:keys [lib as as-alias refer only rename]}]
  (when-not rename
    (let [{:keys [symbols qualifiers]} file
          local (or as as-alias)
          named (or refer only)
          dead-alias (when (and local
                                (not (contains? qualifiers (str local)))
                                (not (contains? qualifiers (str lib))))
                       (str local))
          dead-refers (when (sequential? named)
                        (seq (distinct (map str (remove #(contains? symbols (str %)) named)))))
          entry (cond-> {:path (:path file) :lib (str lib)}
                  dead-alias (assoc :alias dead-alias)
                  dead-refers (assoc :refers (vec dead-refers)))]
      (when (or dead-alias dead-refers)
        entry))))

(defn unused-requires
  "Aliases and referred names declared by a require that the file never uses."
  [files]
  (vec (for [file files
             spec (:libspecs file)
             :let [e (unused-in-libspec file spec)]
             :when e]
         e)))

(defn candidates-of
  "Every cleanup candidate of analyzed files."
  [files]
  {:unused-requires (unused-requires files)
   :unused-private (unused-private files)
   :unused-public (unused-public files)})
