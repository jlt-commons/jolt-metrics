(ns metrics.paths-test
  "Paths relative to the analysis root: globs, test files and resolving loads."
  (:require [clojure.test :refer [deftest is testing]]
            [metrics.paths :as paths]))

(deftest relative-paths
  (is (= "a/b.ss" (paths/relative "root" "root/a/b.ss")))
  (is (= "a/b.ss" (paths/relative "root/" "root/a/b.ss")))
  (is (= "a/b.ss" (paths/relative "./root" "root/a/b.ss")))
  (is (= "a/b.ss" (paths/relative "." "a/b.ss")))
  (is (= "a/b.ss" (paths/relative nil "a/b.ss")))
  (is (= "a/b.ss" (paths/relative "/x/y" "/x/y/a/b.ss")))
  (is (= "other/b.ss" (paths/relative "root" "other/b.ss"))))

(deftest globs
  (testing "a star stays within a directory"
    (is (paths/glob-match? "*.ss" "a.ss"))
    (is (not (paths/glob-match? "*.ss" "x/a.ss")))
    (is (paths/glob-match? "x/*.ss" "x/a.ss")))
  (testing "two stars cross directories"
    (is (paths/glob-match? "vendor/**" "vendor/a/b.ss"))
    (is (paths/glob-match? "**/seed/*" "host/chez/seed/p.ss"))
    (is (paths/glob-match? "**/seed/*" "seed/p.ss"))
    (is (paths/glob-match? "**.ss" "x/y/a.ss")))
  (testing "a question mark is one character"
    (is (paths/glob-match? "a?.ss" "ab.ss"))
    (is (not (paths/glob-match? "a?.ss" "a/.ss"))))
  (testing "everything else is literal"
    (is (paths/glob-match? "a.b+c.ss" "a.b+c.ss"))
    (is (not (paths/glob-match? "a.ss" "axss"))))
  (testing "a bare directory covers what is under it"
    (is (paths/glob-match? "vendor" "vendor/a/b.ss"))
    (is (paths/glob-match? "vendor/" "vendor/a/b.ss"))
    (is (not (paths/glob-match? "vendor" "vendored/a.ss")))))

(deftest excluded
  (is (paths/excluded? ["a/*" "b/**"] "b/c/d.ss"))
  (is (not (paths/excluded? ["a/*"] "b/c.ss")))
  (is (not (paths/excluded? [] "b/c.ss")))
  (is (not (paths/excluded? nil "b/c.ss"))))

(deftest test-files-go-by-directory-name
  (is (paths/test-file? ["test" "tests"] "test/chez/a.ss"))
  (is (paths/test-file? ["test" "tests"] "x/tests/a.clj"))
  (is (not (paths/test-file? ["test" "tests"] "src/testing/a.clj")))
  (is (not (paths/test-file? ["test" "tests"] "src/test.clj")) "a file name is not a directory")
  (is (paths/test-file? ["bench/*"] "bench/a.ss") "or a glob"))
