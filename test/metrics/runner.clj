(ns metrics.runner
  "Runs jolt-metrics' writ specs and behaviour tests.

  Specs are the files under test/metrics named *_spec.clj: each defines a
  clojure.test test, writ-check, that runs writ.spec/check -- the static, law
  and proof gates -- and fails with its report.  Behaviour tests are the
  *_test.clj files.  Both run under clojure.test.

  Discovery is by directory scan, so adding a spec or test file is enough.
  Namespaces given as arguments narrow the run to those."
  (:require [clojure.test :as t]
            [clojure.string :as str]
            [jolt.fs :as fs]))

(defn- path->ns
  "test/metrics/foo_spec.clj -> metrics.foo-spec."
  [p]
  (-> (str p)
      (str/replace #"^.*?\btest/" "")
      (str/replace #"\.clj$" "")
      (str/replace "_" "-")
      (str/replace "/" ".")
      symbol))

(defn- discovered [suffix]
  (->> (fs/list-dir "test/metrics" suffix)
       (map path->ns)
       sort))

(defn- run-tests [nses]
  (doseq [n nses] (require n))
  (reduce
   (fn [acc n]
     (print (str "  test " n " ... ")) (flush)
     (let [t0 (System/currentTimeMillis)
           s  (t/run-tests n)]
       (println (str (- (System/currentTimeMillis) t0) "ms"
                     (when (pos? (+ (:fail s 0) (:error s 0)))
                       (str "  <-- " (:fail s 0) " fail " (:error s 0) " error"))))
       (merge-with + (dissoc acc :type) (dissoc s :type))))
   {:test 0 :pass 0 :fail 0 :error 0}
   nses))

(defn -main
  "Run every spec and test, or only the namespaces named in args."
  [& args]
  (let [only  (when (seq args) (set (map symbol args)))
        pick  (fn [nses] (if only (filterv only nses) nses))
        specs (pick (discovered "*_spec.clj"))
        tests (pick (discovered "*_test.clj"))
        {:keys [test pass fail error] :or {test 0 pass 0 fail 0 error 0}}
        (run-tests (concat specs tests))]
    (println (str "\nSpecs: " (count specs) ", tests: " (count tests) " namespaces."))
    (println (str "Ran " test " tests. " pass " assertions passed, "
                  fail " failures, " error " errors."))
    (flush)
    (System/exit (if (pos? (+ fail error)) 1 0))))
