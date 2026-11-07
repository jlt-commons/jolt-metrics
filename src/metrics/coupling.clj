(ns metrics.coupling
  "Architecture coupling.

  Efferent coupling Ce of a module is the set of modules it depends on; afferent
  coupling Ca is the set depending on it.  Instability is I = Ce / (Ce + Ca):
  0 is maximally stable, 1 maximally unstable, and a module with no dependencies
  at all is read as 0.")

(defn efferent
  "For each edge [from to], from depends on to: {from #{to ...}}."
  [edges]
  (reduce (fn [m [from to]] (update m from (fnil conj #{}) to)) {} edges))

(defn afferent
  "The inverse of efferent: {to #{from ...}}, who depends on each module."
  [edges]
  (reduce (fn [m [from to]] (update m to (fnil conj #{}) from)) {} edges))

(defn instability
  "The instability of a module with efferent Ce and afferent Ca: Ce/(Ce+Ca),
  or 0 when it has no coupling at all."
  [ce ca]
  (let [total (+ ce ca)]
    (if (pos? total) (/ ce total) 0)))

(defn instability-band
  "Band by instability: <= 0.3 stable, <= 0.7 neutral, above that unstable."
  [i]
  (cond (<= i 0.3) :stable (> i 0.7) :unstable :else :neutral))
