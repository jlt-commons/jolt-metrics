(ns metrics.language-test
  "Which language a path is, and what a directory scan covers."
  (:require [clojure.test :refer [deftest is]]
            [metrics.core :as core]
            [metrics.language :as language]
            [jolt.fs :as fs]))

(deftest languages-go-by-extension
  (is (= :clojure (:id (language/of "a/b.clj"))))
  (is (= :clojure (:id (language/of "a/b.CLJC"))))
  (is (= :clojure (:id (language/of "a/b.jolt"))))
  (is (= :scheme (:id (language/of "a/b.ss"))))
  (is (= :scheme (:id (language/of "a.b/c.scm")))))

(deftest anything-else-has-no-language
  (is (nil? (language/of "a/b.txt")))
  (is (nil? (language/of "a/clj")))
  (is (nil? (language/of "a/b.clj-kondo"))))

(deftest a-file-with-no-language-is-reported
  (let [result (core/analyze-files [["a.txt" "hello"]])]
    (is (= ["a.txt"] (map :path (:errors result))))))

(deftest a-directory-scan-covers-both-languages
  (let [dir (str (fs/create-temp-dir))]
    (spit (str dir "/a.ss") "(define (f x) (if x 1 2))")
    (spit (str dir "/b.clj") "(ns b) (defn g [x] x)")
    (spit (str dir "/c.txt") "not code")
    (let [result (core/analyze-dir dir)]
      (is (= #{:scheme :clojure} (set (map :language (:files result)))))
      (is (= 2 (count (:files result))))
      (is (empty? (:errors result))))))
