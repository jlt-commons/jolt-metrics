(ns metrics.thresholds
  "The limits the report judges code by, and a project's own from its config:

    {:thresholds {:cyclomatic {:warning 8}
                  :hotspot {:min-changes 5}
                  :duplicates {:max-distance 0.3}}}

  A project names only what it changes; the rest keep their defaults.  The README
  lists where each default comes from."
  (:require [metrics.compression :as compression]
            [metrics.entropy :as entropy]))

(def defaults
  "Every limit.  For the counts a value over :warning is a warning and over
  :critical is critical; the maintainability index is the other way round, a
  warning at or under :warning and critical under :critical; hotspot risk is a
  warning or critical from its limit up.  Hotspot risk scales a file's changes
  and complexity by the project's most, or by the floors when those are higher,
  so the top file of a young or quiet project isn't at full risk by default."
  {:cyclomatic {:warning 10 :critical 20}
   :cognitive {:warning 15 :critical 25}
   :nesting {:warning 3 :critical 5}
   :lines {:warning 20 :critical 50}
   :params {:warning 4 :critical 7}
   :maintainability {:warning 38.0 :critical 10.0}
   :file-lines {:warning 500 :critical 1000}
   :efferent {:warning 19 :critical 39}
   :hotspot {:warning 0.5 :critical 0.8 :min-changes 3
             :changes-floor 20 :complexity-floor 100}
   :unusual {:z (double entropy/z-limit)}
   :duplicates {:max-distance compression/max-distance :min-tokens compression/min-tokens}})

(def count-rules
  "The rules over counts, where higher is worse and limits are whole numbers."
  [:cyclomatic :cognitive :nesting :lines :params :file-lines :efferent])

(defn- whole? [x] (and (integer? x) (>= x 0)))

(defn- share? [x] (and (number? x) (<= 0 x 1)))

(defn- positive? [x] (and (number? x) (pos? x)))

(def ^:private checks
  "What each key's value has to be, with what to say when it isn't."
  (merge
   (zipmap count-rules (repeat {:warning [whole? "a whole number"]
                                :critical [whole? "a whole number"]}))
   {:maintainability {:warning [number? "a number"] :critical [number? "a number"]}
    :hotspot {:warning [share? "between 0 and 1"] :critical [share? "between 0 and 1"]
              :min-changes [whole? "a whole number"]
              :changes-floor [whole? "a whole number"]
              :complexity-floor [whole? "a whole number"]}
    :unusual {:z [positive? "a positive number"]}
    :duplicates {:max-distance [share? "between 0 and 1"]
                 :min-tokens [whole? "a whole number"]}}))

(defn- key-problems
  "What is wrong with one key's map of limits."
  [k v]
  (let [allowed (get checks k)]
    (cond
      (nil? allowed) [(str "unknown threshold " k)]
      (not (map? v)) [(str k " should be a map, like " (pr-str (get defaults k)))]
      :else (for [[sub x] v
                  :let [[ok? what] (get allowed sub)]
                  :when (not (and ok? (ok? x)))]
              (if ok?
                (str k " " sub " should be " what)
                (str "unknown threshold " k " " sub))))))

(defn- order-problems
  "Limits that are each fine but contradict each other once merged."
  [merged]
  (concat
   (for [k (conj count-rules :hotspot)
         :let [{:keys [warning critical]} (get merged k)]
         :when (> warning critical)]
     (str k " :warning should be at most its :critical"))
   (let [{:keys [warning critical]} (:maintainability merged)]
     (when (< warning critical)
       [":maintainability :warning should be at least its :critical, since lower is worse"]))))

(defn with-defaults
  "The project's thresholds over the defaults, key by key."
  [thresholds]
  (merge-with merge defaults thresholds))

(defn problem
  "What is wrong with a project's thresholds, or nil."
  [thresholds]
  (cond
    (nil? thresholds) nil
    (not (map? thresholds)) ":thresholds should be a map"
    :else (first (or (seq (mapcat (fn [[k v]] (key-problems k v)) thresholds))
                     (order-problems (with-defaults thresholds))))))
