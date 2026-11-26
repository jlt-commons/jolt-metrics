(ns metrics.references
  "What refers to a project's vars from outside the source it analyzes: the hooks
  a clj-kondo config names, the code in bb.edn and deps.edn :tasks, and source
  files the project excludes.  None of it is reported on; it only keeps the
  vars it names off the unused lists.

  A config's references are ns/name strings, like the qualified symbols
  metrics.mentions finds.  An excluded file gives its jolt-lsp analysis and its
  written qualified symbols, through its language's adapter; only a language
  with an index has references of this kind, so a Scheme file left out never
  counts by name."
  (:require [clojure.edn :as edn]
            [clojure.string :as str]
            [metrics.language :as language]
            [metrics.mentions :as mentions]
            [metrics.reader :as reader]
            [jolt.fs :as fs]))

(def ^:private hook-kinds
  "The :hooks entries whose symbols name a macro and the hook fn for it."
  [:analyze-call :macroexpand])

(defn kondo-hooks
  "The ns/name strings a clj-kondo config's :hooks name, macros and hook fns."
  [config]
  (set (for [kind hook-kinds
             :let [hooks (get-in config [:hooks kind])]
             :when (map? hooks)
             s (mapcat identity hooks)
             :when (qualified-symbol? s)]
         (str s))))

(defn- libspec-parts
  "A libspec as [lib aliases refers], or nil when it isn't one."
  [spec]
  (cond
    (symbol? spec) [spec {} []]
    (and (sequential? spec) (symbol? (first spec)))
    (let [lib (first spec)
          {:keys [as as-alias refer]} (apply hash-map (rest spec))
          local (or as as-alias)]
      [lib (if local {(str local) (str lib)} {}) (if (sequential? refer) refer [])])
    :else nil))

(defn- requires-scope
  "{:aliases {alias lib} :refers {name lib}} of the libspecs, as strings."
  [specs]
  (reduce (fn [scope [lib aliases refers]]
            (-> scope
                (update :aliases merge aliases)
                (update :refers into (for [r refers] [(str r) (str lib)]))))
          {:aliases {} :refers {}}
          (keep libspec-parts (when (sequential? specs) specs))))

(defn- resolve-in
  "The ns/name a task symbol names: an alias resolved, a referred name to its lib,
  any other bare name to babashka.tasks, which tasks refer implicitly."
  [{:keys [aliases refers]} s]
  (let [nm (name s)]
    (if-let [q (namespace s)]
      (str (get aliases q q) "/" nm)
      (str (get refers nm "babashka.tasks") "/" nm))))

(defn- symbols-in
  "The symbols of a form that may name vars: special forms don't."
  [form]
  (remove special-symbol? (filter symbol? (tree-seq coll? seq form))))

(defn- task-parts
  "[code requires] of a :tasks entry: a map's :task and :requires, or the body
  itself.  A string body is a shell command."
  [v]
  (if (map? v) [(:task v) (:requires v)] [v nil]))

(defn task-references
  "The ns/name strings a :tasks map's code names, each task with the map's
  :requires and its own in scope."
  [tasks]
  (let [shared (:requires tasks)
        code (concat (for [k [:init :enter :leave]] [(get tasks k) nil])
                     (for [[k v] tasks :when (symbol? k)] (task-parts v)))]
    (set (for [[form requires] code
               :let [scope (requires-scope (concat shared requires))]
               s (symbols-in form)]
           (resolve-in scope s)))))

(defn- project-tasks
  "The :tasks map of a bb.edn or deps.edn's text, read as code since task bodies
  use reader macros EDN lacks."
  [text]
  (let [config (first (reader/read-forms text))
        tasks (when (map? config) (:tasks config))]
    (when (map? tasks) tasks)))

(defn config-references
  "The ns/name strings a config file names, by its file name; none when it
  doesn't read."
  [path text]
  (try
    (case (last (str/split path #"/"))
      "config.edn" (kondo-hooks (edn/read-string text))
      ("bb.edn" "deps.edn") (task-references (project-tasks text))
      #{})
    (catch Exception _ #{})))

(defn- kondo-config? [path]
  (boolean (re-find #"(^|/)(\.clj-kondo|clj-kondo\.exports)/(.*/)?config\.edn$" path)))

(defn- config-path? [path]
  (or (kondo-config? path) (boolean (re-find #"(^|/)(bb|deps)\.edn$" path))))

(defn config-paths
  "The clj-kondo configs, bb.edn and deps.edn files under dir, .clj-kondo
  included although it is hidden, less what left-out? says."
  [dir left-out?]
  (let [kondo (str dir "/.clj-kondo")]
    (->> (concat (fs/glob dir "**{bb.edn,deps.edn,config.edn}")
                 (when (fs/directory? kondo) (fs/glob kondo "**config.edn")))
         (map str)
         (filter config-path?)
         (remove left-out?)
         distinct)))

(defn- written-in
  "The qualified symbols parse finds written in source, none if it can't read it."
  [parse source]
  (try (mentions/qualified-symbols (:forms (parse source)))
       (catch Throwable _ #{})))

(defn- excluded-file
  "What an excluded file refers to: its jolt-lsp analysis and written qualified
  symbols, or nil when its language has no index."
  [path]
  (let [{:keys [index parse]} (language/of path)]
    (when index
      (let [source (slurp path)]
        {:path path
         :index-analysis (index path source)
         :qualified-symbols (written-in parse source)}))))

(defn collect
  "The references from outside the analysis: {:written #{ns/name} :files [...]},
  the files being the excluded-paths whose language has an index."
  [config-paths excluded-paths]
  (let [files (vec (keep excluded-file excluded-paths))]
    {:written (into (set (mapcat :qualified-symbols files))
                    (mapcat #(config-references % (slurp %)))
                    config-paths)
     :files (filterv :index-analysis files)}))
