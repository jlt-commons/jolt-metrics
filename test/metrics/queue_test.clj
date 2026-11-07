(ns metrics.queue-test
  "The refactoring queue: what to fix first, where, and why."
  (:require [clojure.test :refer [deftest is]]
            [clojure.string :as str]
            [metrics.core :as core]
            [metrics.report :as report]))

(defn- branchy
  "A function whose cond has n clauses."
  [fname n]
  (str "(defn " fname " [x]\n  (cond\n"
       (apply str (for [i (range n)] (str "    (= x " i ") (f" i " x)\n")))
       "    :else nil))\n"))

(defn- deep
  "A function nesting n ifs."
  [fname n]
  (str "(defn " fname " [x] "
       (apply str (repeat n "(if x "))
       "1"
       (apply str (repeat n " 2)"))
       ")\n"))

(defn- queue [src & [opts]]
  (:queue (core/analyze-files [["a.clj" src]] opts)))

(defn- entry [q n] (first (filter #(= n (:name %)) q)))

(deftest healthy-code-queues-nothing
  (is (empty? (queue "(ns a)\n(defn f [x] (inc x))\n"))))

(deftest a-complex-function-is-queued-with-its-location
  (let [e (entry (queue (str "\n" (branchy "big" 25))) "big")]
    (is (= "a.clj" (:path e)))
    (is (= :function (:kind e)))
    (is (= 2 (:line e)))
    (is (= 29 (:end-line e)))
    (is (= :critical (:severity e)))))

(deftest entries-say-which-rules-broke
  (let [e (entry (queue (branchy "big" 25)) "big")
        cc (first (filter #(= :cyclomatic (:rule %)) (:violations e)))]
    (is (= {:rule :cyclomatic :value 26 :limit 20 :band :critical} cc))))

(deftest entries-say-what-to-do
  (let [e (entry (queue (branchy "big" 25)) "big")]
    (is (seq (:actions e)))
    (is (every? string? (:actions e)))))

(deftest a-warning-is-not-critical
  (is (= :warning (:severity (entry (queue (branchy "mid" 12)) "mid")))))

(deftest deep-nesting-is-flagged
  (let [e (entry (queue (deep "d" 6)) "d")]
    (is (some #(= :nesting (:rule %)) (:violations e)))))

(deftest many-parameters-are-flagged
  (let [e (entry (queue "(defn p [a b c d e f g h] [a b c d e f g h])") "p")]
    (is (some #(= :params (:rule %)) (:violations e)))))

(deftest long-functions-are-flagged
  (let [body (apply str (repeat 60 "  (f x)\n"))
        e (entry (queue (str "(defn long-one [x]\n" body ")")) "long-one")]
    (is (some #(= :lines (:rule %)) (:violations e)))))

(deftest a-critically-low-index-is-flagged
  (let [e (entry (queue (branchy "huge" 120)) "huge")]
    (is (some #(= :maintainability (:rule %)) (:violations e)))))

(deftest a-middling-index-alone-queues-nothing
  (let [src "(defn f [a b c]\n  (let [x (+ a b)\n        y (* b c)\n        z (- x y)\n        w (/ z 2)\n        v (str x y z w)\n        u (vector x y z w v)\n        t (hash-map :x x :y y :z z)\n        s (assoc t :u u :v v :w w)]\n    (when (pos? a)\n      (merge s {:a a :b b :c c}))))"
        f (first (:functions (core/analyze-source "a.clj" src)))]
    (is (< 40 (:maintainability-index-without-comments f) 60))
    (is (empty? (queue src)))))

(deftest tests-are-not-queued
  (is (empty? (queue (str/replace (branchy "t" 25) "(defn t [x]" "(deftest t")))))

(deftest worse-comes-first
  (let [q (queue (str (branchy "mid" 12) (branchy "big" 25)))]
    (is (= ["big" "mid"] (map :name q)))))

(deftest a-big-file-is-queued
  (let [big (apply str (repeat 600 "(def x 1)\n"))
        e (first (queue big))]
    (is (= :file (:kind e)))
    (is (= "a.clj" (:name e)))
    (is (= 1 (:line e)))
    (is (some #(= :file-lines (:rule %)) (:violations e)))))

(deftest heavy-efferent-coupling-is-queued
  (let [deps (for [i (range 21)] (str "d" i))
        hub (str "(ns hub (:require " (str/join " " (map #(str "[" % "]") deps)) "))")
        q (:queue (core/analyze-files (cons ["hub.clj" hub]
                                            (for [d deps] [(str d ".clj") (str "(ns " d ")")]))))
        e (first (filter #(= "hub.clj" (:path %)) q))]
    (is (some #(= :efferent (:rule %)) (:violations e)))))

;; --- churn ---------------------------------------------------------------

(deftest churn-raises-priority
  (let [src [["a.clj" (branchy "a" 12)] ["b.clj" (branchy "b" 12)]]
        q (:queue (core/analyze-files src {:changes {"b.clj" 8 "a.clj" 1} :days 90}))]
    (is (= ["b" "a"] (map :name (filter #(= :function (:kind %)) q))))
    (is (= 8 (:changes (first (filter #(= "b" (:name %)) q)))))))

(deftest hotspots-combine-churn-and-complexity
  (let [src [["hot.clj" (branchy "hot" 12)]
             ["cold.clj" (branchy "cold" 12)]
             ["busy.clj" "(defn busy [x] (inc x))"]]
        {:keys [hotspots]} (core/analyze-files
                            src {:changes {"hot.clj" 10 "cold.clj" 1 "busy.clj" 10} :days 90})]
    (is (= "hot.clj" (:path (first hotspots))))
    (is (= 1.0 (:risk (first hotspots))))
    (is (not-any? #(= "cold.clj" (:path %)) hotspots) "too few changes to matter")))

(deftest a-single-commit-makes-no-hotspots
  (let [{:keys [hotspots]} (core/analyze-files [["a.clj" (branchy "a" 25)]]
                                               {:changes {"a.clj" 1} :days 90})]
    (is (empty? hotspots))))

(deftest a-hot-file-is-queued
  (let [src [["hot.clj" (branchy "hot" 8)] ["other.clj" (branchy "other" 3)]]
        q (:queue (core/analyze-files src {:changes {"hot.clj" 10 "other.clj" 3} :days 90}))
        e (first (filter #(and (= :file (:kind %)) (= "hot.clj" (:path %))) q))]
    (is (some #(= :hotspot (:rule %)) (:violations e)))))

;; --- cleanup candidates ---------------------------------------------------

(defn- cleanup [& pairs]
  (:cleanup (core/analyze-files (partition 2 pairs))))

(deftest unused-private-functions
  (let [{:keys [unused-private]} (cleanup "a.clj" "(ns a) (defn- dead [] 1) (defn- live [] 2) (defn go [] (live))")]
    (is (= [["a.clj" "dead"]] (map (juxt :path :name) unused-private)))))

(deftest recursion-does-not-keep-a-function-alive
  (is (= ["loopy"] (map :name (:unused-private
                               (cleanup "a.clj" "(ns a) (defn- loopy [x] (loopy x))"))))))

(deftest a-private-var-counts-as-private
  (is (= ["dead"] (map :name (:unused-private
                              (cleanup "a.clj" "(ns a) (defn ^:private dead [] 1)"))))))

(deftest var-quote-is-a-reference
  (is (empty? (:unused-private (cleanup "a.clj" "(ns a) (defn- f [] 1) (def g #'f)")))))

(deftest unused-public-functions-look-across-files
  (let [{:keys [unused-public]} (cleanup "a.clj" "(ns a) (defn used [] 1) (defn dead [] 2)"
                                         "b.clj" "(ns b (:require [a :as x])) (defn go [] (x/used))")]
    (is (= #{"dead" "go"} (set (map :name unused-public))))))

(deftest entry-points-and-other-kinds-are-not-unused
  (let [{:keys [unused-public]} (cleanup "a.clj" "(ns a) (defn -main [] 1) (defmethod m :a [x] 1) (deftest t 1) (defrecord R [] P (n [_] 1))")]
    (is (empty? unused-public))))

(deftest unused-requires
  (let [{:keys [unused-requires]}
        (cleanup "a.clj" "(ns a (:require [b.used :as u] [b.dead :as d] [b.load] [b.refd :refer [x y]] [b.all :refer :all]))
                          (defn f [] (u/g) (x))")]
    (is (= #{{:path "a.clj" :lib "b.dead" :alias "d"}
             {:path "a.clj" :lib "b.refd" :refers ["y"]}}
           (set unused-requires)))))

(deftest alias-keywords-and-namespaced-maps-count-as-use
  (let [{:keys [unused-requires]}
        (cleanup "a.clj" "(ns a (:require [b :as k] [c :as m] [e :as t])) (def v ::k/x) (def w #::m{:a 1}) (defmacro q [] `(t/f))")]
    (is (empty? unused-requires))))

(deftest a-fully-qualified-use-counts
  (is (empty? (:unused-requires (cleanup "a.clj" "(ns a (:require [b.c :as d])) (def v (b.c/f))")))))

(deftest the-model-internals-stay-internal
  (let [file (first (:files (core/analyze-files [["a.clj" "(ns a) (defn f [] 1)"]])))]
    (is (not-any? #(contains? file %) [:symbols :qualifiers :libspecs]))))

;; --- the report ------------------------------------------------------------

(deftest the-report-leads-with-the-queue
  (let [md (report/render (core/analyze-files [["a.clj" (branchy "big" 25)]]))]
    (is (str/includes? md "## Refactoring queue"))
    (is (< (str/index-of md "## Refactoring queue") (str/index-of md "## Files")))
    (is (re-find #"`big` \(a\.clj:1-28\)" md))
    (is (str/includes? md "**critical**"))))

(deftest the-report-says-when-nothing-is-queued
  (is (str/includes? (report/render (core/analyze-files [["a.clj" "(defn f [x] (inc x))"]]))
                     "_nothing over the thresholds_")))

(deftest the-report-counts-in-english
  (let [md (report/render (core/analyze-files [["a.clj" (branchy "big" 25)]]
                                              {:changes {"a.clj" 1} :days 9}))]
    (is (str/includes? md "1 param,"))
    (is (str/includes? md "1 change."))))

(deftest the-report-caps-the-queue
  (let [src (apply str (for [i (range 5)] (branchy (str "f" i) 25)))
        md (report/render (core/analyze-files [["a.clj" src]]) {:top 2})]
    (is (str/includes? md "and 3 more"))
    (is (not (str/includes? md "`f4`")))))

(deftest the-report-states-its-thresholds
  (is (str/includes? (report/render (core/analyze-files [])) "## Thresholds")))

(deftest the-report-lists-cleanup-candidates
  (let [md (report/render (core/analyze-files [["a.clj" "(ns a (:require [b :as d])) (defn- dead [] 1)"]]))]
    (is (str/includes? md "## Cleanup candidates"))
    (is (str/includes? md "`dead`"))
    (is (str/includes? md "`b`"))))

(deftest the-report-shows-hotspots-when-there-is-history
  (let [md (fn [opts] (report/render (core/analyze-files [["a.clj" (branchy "a" 12)]] opts)))]
    (is (str/includes? (md {:changes {"a.clj" 9} :days 30}) "## Hotspots"))
    (is (str/includes? (md {:changes {"a.clj" 9} :days 30}) "30 days"))
    (is (not (str/includes? (md nil) "## Hotspots")))))
