(ns metrics.thresholds-test
  "A project's own thresholds: what the config takes, and that the queue, the
  findings and every report format go by them."
  (:require [clojure.test :refer [deftest is]]
            [clojure.string :as str]
            [metrics.thresholds :as t]
            [metrics.core :as core]
            [metrics.report :as report]
            [metrics.json :as json]
            [metrics.html :as html]))

;; --- the config ------------------------------------------------------------------

(deftest no-thresholds-are-the-defaults
  (is (= t/defaults (t/with-defaults nil))))

(deftest a-project-names-only-what-it-changes
  (let [merged (t/with-defaults {:cyclomatic {:warning 8}})]
    (is (= {:warning 8 :critical 20} (:cyclomatic merged)))
    (is (= (:cognitive t/defaults) (:cognitive merged)))))

(deftest the-defaults-are-consistent
  (is (nil? (t/problem t/defaults))))

(deftest good-thresholds-have-no-problem
  (is (nil? (t/problem {:lines {:warning 30 :critical 80}
                        :maintainability {:warning 50 :critical 20}
                        :hotspot {:warning 0.4 :min-changes 5}
                        :unusual {:z 2.5}
                        :duplicates {:max-distance 0.3 :min-tokens 60}}))))

(deftest unknown-thresholds-are-problems
  (is (= "unknown threshold :complexity" (t/problem {:complexity {:warning 1}})))
  (is (= "unknown threshold :cyclomatic :warn" (t/problem {:cyclomatic {:warn 1}}))))

(deftest values-of-the-wrong-kind-are-problems
  (is (= ":thresholds should be a map" (t/problem [1 2])))
  (is (str/starts-with? (t/problem {:cyclomatic 10}) ":cyclomatic should be a map"))
  (is (= ":cyclomatic :warning should be a whole number" (t/problem {:cyclomatic {:warning 2.5}})))
  (is (= ":hotspot :critical should be between 0 and 1" (t/problem {:hotspot {:critical 2}})))
  (is (= ":unusual :z should be a positive number" (t/problem {:unusual {:z 0}}))))

(deftest limits-that-contradict-once-merged-are-problems
  ;; 30 alone is a fine number, but over the default critical of 20
  (is (= ":cyclomatic :warning should be at most its :critical"
         (t/problem {:cyclomatic {:warning 30}})))
  (is (str/starts-with? (t/problem {:maintainability {:warning 5.0}})
                        ":maintainability :warning should be at least its :critical")))

;; --- the analysis ----------------------------------------------------------------

(defn- branchy
  "A function whose cond has n clauses: cyclomatic complexity n + 1."
  [fname n]
  (str "(defn " fname " [x]\n  (cond\n"
       (apply str (for [i (range n)] (str "    (= x " i ") (f" i " x)\n")))
       "    :else nil))\n"))

(defn- analyzed [src thresholds]
  (core/analyze-files [["a.clj" (str "(ns a)\n" src)]] {:thresholds thresholds}))

(deftest the-queue-goes-by-the-projects-limits
  (let [src (branchy "f" 7)]
    (is (empty? (:queue (analyzed src nil))))
    (let [[e] (:queue (analyzed src {:cyclomatic {:warning 5 :critical 7}}))]
      (is (= :critical (:severity e)))
      (is (= {:rule :cyclomatic :value 8 :limit 7 :band :critical}
             (first (filter #(= :cyclomatic (:rule %)) (:violations e))))))))

(deftest the-result-carries-its-limits
  (is (= 5 (get-in (analyzed "" {:cyclomatic {:warning 5}}) [:thresholds :cyclomatic :warning])))
  (is (= t/defaults (:thresholds (analyzed "" nil)))))

(def ^:private copies
  ;; two copies of one forty-odd-token body under different names
  (str/join "\n" (for [n ["a" "b"]]
                   (str "(defn " n " [xs ys]\n"
                        "  (let [n (count xs) m (count ys)]\n"
                        "    (when (and (pos? n) (pos? m))\n"
                        "      {:mean (/ (reduce + xs) n) :other (/ (reduce + ys) m)\n"
                        "       :sum (+ (reduce + xs) (reduce + ys)) :n (+ n m)})))\n"))))

(deftest duplicates-go-by-the-projects-limits
  (is (= 1 (count (:duplicates (analyzed copies nil)))))
  (is (= [] (:duplicates (analyzed copies {:duplicates {:min-tokens 500}})))))

;; --- the reports -----------------------------------------------------------------

(def ^:private strict
  {:cyclomatic {:warning 6 :critical 9}
   :hotspot {:min-changes 7}
   :unusual {:z 2.5}
   :duplicates {:max-distance 0.2 :min-tokens 45}})

(deftest markdown-shows-the-limits-it-used
  (let [md (report/render (analyzed copies strict))]
    (is (str/includes? md "| Cyclomatic complexity | > 6 | > 9 |"))
    (is (str/includes? md "| Hotspot risk (at least 7 changes) |"))
    (is (str/includes? md "more than 2.5 standard deviations"))
    (is (str/includes? md "pairs at or under 0.2 are listed"))
    (is (str/includes? md "definitions under 45 tokens"))))

(deftest json-has-the-limits-it-used
  (let [th (get (json/report (analyzed copies strict)) "thresholds")]
    (is (= {"warning" 6 "critical" 9} (get th "cyclomatic")))
    (is (= {"max_distance" 0.2 "min_tokens" 45} (get th "duplicates")))))

(deftest html-shows-the-limits-it-used
  (let [page (html/render (analyzed copies strict))]
    (is (str/includes? page "&gt; 6"))
    (is (str/includes? page "at least 7 changes"))
    (is (str/includes? page "pairs at or under 0.2"))))
