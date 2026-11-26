(ns metrics.baseline-test
  "Comparing a queue with one from an earlier run: what a refactor fixed, what
  it made better or worse, and what is new."
  (:require [clojure.test :refer [deftest is testing]]
            [clojure.string :as str]
            [metrics.baseline :as baseline]
            [metrics.cli :as cli]
            [metrics.core :as core]
            [metrics.report :as report]
            [jolt.fs :as fs]))

(defn- entry [nm score & {:as more}]
  (merge {:kind :function :path "a.clj" :name nm :line 1 :score score
          :metrics {:cognitive 20 :cyclomatic 12}}
         more))

(deftest an-empty-baseline-makes-everything-new
  (is (= {:fixed [] :improved [] :worse [] :new [(entry "f" 3.0)] :unchanged 0 :not-analyzed []}
         (baseline/compare-queues [] [(entry "f" 3.0)]))))

(deftest entries-match-by-kind-path-and-name-not-line
  (testing "a definition that moved is the same entry"
    (is (= 1 (:unchanged (baseline/compare-queues [(entry "f" 3.0)]
                                                  [(entry "f" 3.0 :line 40)])))))
  (testing "the same name in another file is another entry"
    (let [c (baseline/compare-queues [(entry "f" 3.0)] [(entry "f" 3.0 :path "b.clj")])]
      (is (= ["a.clj"] (map :path (:fixed c))))
      (is (= ["b.clj"] (map :path (:new c)))))))

(deftest what-left-the-queue-is-fixed
  (is (= [(entry "f" 3.0)]
         (:fixed (baseline/compare-queues [(entry "f" 3.0) (entry "g" 1.0)]
                                          [(entry "g" 1.0)])))))

(deftest a-lower-score-is-improved-and-says-what-changed
  (let [after (entry "f" 1.0 :metrics {:cognitive 12 :cyclomatic 12})
        c (baseline/compare-queues [(entry "f" 3.0)] [after])]
    (is (= [{:entry after :before-score 3.0 :changed {:cognitive [20 12]}}]
           (:improved c)))
    (is (empty? (:worse c)))))

(deftest a-higher-score-is-worse
  (let [c (baseline/compare-queues [(entry "f" 1.0)] [(entry "f" 3.0)])]
    (is (= [3.0] (map (comp :score :entry) (:worse c))))
    (is (= [1.0] (map :before-score (:worse c))))))

(deftest entries-sharing-a-name-pair-up-in-order
  (let [c (baseline/compare-queues [(entry "f" 5.0) (entry "f" 3.0)]
                                   [(entry "f" 5.0 :line 9) (entry "f" 3.0 :line 20)])]
    (is (= 2 (:unchanged c)))
    (is (empty? (:worse c)))
    (is (empty? (:improved c)))))

(deftest entries-of-files-not-analyzed-now-are-not-fixed
  (let [c (baseline/compare-queues [(entry "f" 3.0) (entry "g" 2.0 :path "b.clj")] []
                                   #{"b.clj"})]
    (is (= ["g"] (map :name (:fixed c))))
    (is (= ["f"] (map :name (:not-analyzed c))))))

(deftest worst-changes-come-first
  (let [c (baseline/compare-queues [(entry "a" 9.0) (entry "b" 9.0) (entry "c" 1.0) (entry "d" 1.0)]
                                   [(entry "a" 8.0) (entry "b" 2.0) (entry "c" 5.0) (entry "d" 2.0)])]
    (is (= ["b" "a"] (map (comp :name :entry) (:improved c))))
    (is (= ["c" "d"] (map (comp :name :entry) (:worse c))))))

(def ^:private branchy
  (str "(ns a)\n(defn big [x]\n  (cond\n"
       (apply str (for [i (range 25)] (str "    (= x " i ") (f" i " x)\n")))
       "    :else nil))\n"))

(deftest the-result-carries-the-comparison
  (let [before (core/analyze-files [["a.clj" branchy]] {:abstractions false})
        after (baseline/with-baseline
               (core/analyze-files [["a.clj" "(ns a)\n(defn big [x] (f x))\n"]] {:abstractions false})
               (:queue before))]
    (is (= ["big"] (map :name (get-in after [:since-baseline :fixed]))))
    (is (nil? (:since-baseline before)))
    (testing "the Markdown report says what was fixed"
      (let [md (report/render after {:top 25})]
        (is (str/includes? md "## Since the baseline"))
        (is (str/includes? md "1 fixed"))
        (is (str/includes? md "big"))))))

(deftest the-cli-reads-a-baseline-from-an-edn-report
  (let [dir (str (fs/create-temp-dir))
        edn (str (fs/create-temp-dir) "/before.edn")]
    (spit (str dir "/a.clj") branchy)
    (is (zero? (:exit (cli/run [dir edn "--no-git" "--no-abstractions"]))))
    (spit (str dir "/a.clj") "(ns a)\n(defn big [x] (f x))\n")
    (let [{:keys [exit out]} (cli/run [dir "--no-git" "--no-abstractions" "--baseline" edn])]
      (is (zero? exit))
      (is (str/includes? out "1 fixed")))
    (testing "a baseline that can't be read is bad usage"
      (is (= 2 (:exit (cli/run [dir "--no-git" "--baseline" (str dir "/missing.edn")])))))
    (testing "so is one whose entries aren't queue entries"
      (let [bad (str (fs/create-temp-dir) "/bad.edn")]
        (spit bad (pr-str {:queue [{:kind :function :path "a.clj" :name "big"}]}))
        (is (= 2 (:exit (cli/run [dir "--no-git" "--no-abstractions" "--baseline" bad]))))))))

(deftest a-file-that-no-longer-parses-is-not-fixed
  (let [before (core/analyze-files [["a.clj" branchy]] {:abstractions false})
        after (baseline/with-baseline
               (core/analyze-files [["a.clj" "(ns a)\n(defn big [x] (f x)"]] {:abstractions false})
               (:queue before))]
    (is (empty? (get-in after [:since-baseline :fixed])))
    (is (= ["big"] (map :name (get-in after [:since-baseline :not-analyzed]))))
    (is (str/includes? (report/render after {:top 25}) "not analyzed"))))
