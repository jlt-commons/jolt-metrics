(ns metrics.ignore-test
  "#_:jolt-metrics/ignore before a form leaves that form's findings out of the
  report, and one that leaves nothing out is a finding of its own."
  (:require [clojure.test :refer [deftest is testing]]
            [metrics.scanner :as scanner]
            [metrics.core :as core]))

(deftest the-scanner-finds-markers
  (is (= [{:line 2 :column 1 :from 3 :to 4 :rules :all}]
         (:ignores (scanner/scan "(ns a)\n#_:jolt-metrics/ignore\n(defn f []\n  (do x))"))))
  (is (= [{:line 1 :column 1 :from 1 :to 1 :rules #{:redundant-do :lines}}]
         (:ignores (scanner/scan "#_{:jolt-metrics/ignore [:redundant-do :lines]} (do x)"))))
  (testing "clj-kondo's marker is kept apart"
    (is (= [{:line 1 :column 1 :from 1 :to 1 :rules :all :clj-kondo true}]
           (:ignores (scanner/scan "#_:clj-kondo/ignore (do x)")))))
  (testing "other discards, strings and comments are not markers"
    (is (empty? (:ignores (scanner/scan "#_(f) \"#_:jolt-metrics/ignore\" ; #_:jolt-metrics/ignore\n(g)"))))))

(defn- analyze [source]
  (core/analyze-files [["src/a.clj" source]] {:abstractions false}))

(defn- rules [result]
  (map :rule (:lints result)))

(deftest a-marker-drops-the-lints-of-its-form
  (is (empty? (rules (analyze "(ns a)\n#_:jolt-metrics/ignore\n(defn f\n  \"Do it.\"\n  [x]\n  (do (g x)))"))))
  (is (= [:redundant-do]
         (rules (analyze "(ns a)\n#_:jolt-metrics/ignore\n(defn f \"Do it.\" [x] x)\n(defn g \"Do it.\" [x] (do x))")))))

(deftest a-marker-can-name-its-rules
  (is (empty? (rules (analyze "#_{:jolt-metrics/ignore [:redundant-do]} (do x)"))))
  (is (= [:redundant-ignore :redundant-do]
         (rules (analyze "#_{:jolt-metrics/ignore [:loop-without-recur]} (do x)")))))

(deftest a-marker-that-drops-nothing-is-redundant
  (let [[f] (:lints (analyze "(ns a)\n#_:jolt-metrics/ignore\n(def x 1)"))]
    (is (= {:path "src/a.clj" :line 2 :column 1 :rule :redundant-ignore :level :info
            :message "Redundant ignore"}
           f))))

(deftest clj-kondo-markers-drop-lints-but-are-never-redundant
  (is (empty? (rules (analyze "#_:clj-kondo/ignore (do x)"))))
  (is (empty? (rules (analyze "#_:clj-kondo/ignore (inc x)")))))

(def ^:private long-fn
  (str "(defn f\n  \"Do it.\"\n  [x a b c d]\n" (apply str (repeat 30 "  (g x)\n")) "  x)\n"))

(deftest a-marker-drops-queue-entries
  (is (= ["f"] (map :name (:queue (analyze long-fn)))))
  (is (empty? (:queue (analyze (str "#_:jolt-metrics/ignore\n" long-fn)))))
  (testing "naming a rule removes only that rule's violation"
    (let [entries (:queue (analyze (str "#_{:jolt-metrics/ignore [:params]}\n" long-fn)))]
      (is (= [[:lines]] (map #(map :rule (:violations %)) entries))))))

(deftest a-marker-drops-cleanup-candidates
  (is (= ["g"] (map :name (get-in (analyze "(defn- g [] 1)") [:cleanup :unused-private]))))
  (is (empty? (get-in (analyze "#_:jolt-metrics/ignore\n(defn- g [] 1)") [:cleanup :unused-private]))))
