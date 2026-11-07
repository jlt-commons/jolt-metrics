(ns metrics.scheme-deps-test
  "What a Scheme file depends on: the files it loads and the libraries it imports."
  (:require [clojure.test :refer [deftest is testing]]
            [metrics.core :as core]
            [metrics.scheme.deps :as deps]
            [metrics.scheme.reader :as r]))

(defn- of [src] (deps/dependencies (r/read-forms src)))

(deftest loads-and-includes-are-file-paths
  (is (= ["a.ss"] (:load-paths (of "(load \"a.ss\")"))))
  (is (= ["a.ss"] (:load-paths (of "(include \"a.ss\")"))))
  (is (= ["a.ss"] (:load-paths (of "(##include \"a.ss\")"))))
  (is (= ["a.ss" "b.ss"] (:load-paths (of "(load \"a.ss\") (load \"b.ss\") (load \"a.ss\")")))))

(deftest a-load-counts-wherever-it-is
  (is (= ["a.ss"] (:load-paths (of "(define (f) (when x (load \"a.ss\")))")))))

(deftest only-a-literal-path-can-be-known
  (is (empty? (:load-paths (of "(load (string-append dir \"a.ss\"))"))))
  (is (empty? (:load-paths (of "(load path)")))))

(deftest quoted-data-loads-nothing
  (is (empty? (:load-paths (of "(define x '(load \"a.ss\"))"))))
  (is (empty? (:load-paths (of "(define x `(load \"a.ss\"))")))))

(deftest imports-name-libraries
  (is (= ["chezscheme" "foo.bar"] (:imports (of "(import (chezscheme) (foo bar))")))))

(deftest import-sets-are-unwrapped
  (testing "whatever wraps a library, it is the library that is depended on"
    (is (= ["a.b"] (:imports (of "(import (only (a b) x))"))))
    (is (= ["a.b"] (:imports (of "(import (except (a b) x y))"))))
    (is (= ["a.b"] (:imports (of "(import (prefix (a b) p:))"))))
    (is (= ["a.b"] (:imports (of "(import (rename (a b) (x y)))"))))
    (is (= ["a.b"] (:imports (of "(import (for (a b) run))"))))
    (is (= ["a.b"] (:imports (of "(import (library (a b)))"))))
    (is (= ["a.b"] (:imports (of "(import (prefix (only (a b) x) p:))"))))))

(deftest a-library-version-is-not-part-of-its-name
  (is (= ["rnrs.base"] (:imports (of "(import (rnrs base (6)))")))))

(deftest importing-a-local-module-is-no-dependency
  (is (empty? (:imports (of "(import m)")))))

(deftest a-library-has-a-namespace-and-imports
  (let [d (of "(library (foo bar (1)) (export f) (import (chezscheme) (a b)) (define (f) 1))")]
    (is (= "foo.bar" (:namespace d)))
    (is (= ["chezscheme" "a.b"] (:imports d)))))

(deftest a-plain-file-has-no-namespace
  (is (nil? (:namespace (of "(define (f) 1)")))))

;; --- resolved across a project ---------------------------------------------

(defn- edges [pairs & [opts]]
  (set (:edges (core/analyze-files pairs opts))))

(deftest a-load-is-relative-to-the-project-root
  (is (= #{["host/a.ss" "host/b.ss"]}
         (edges [["host/a.ss" "(load \"host/b.ss\")"] ["host/b.ss" "(define x 1)"]]))))

(deftest a-load-can-be-relative-to-its-file
  (is (= #{["host/a.ss" "host/b.ss"]}
         (edges [["host/a.ss" "(load \"b.ss\")"] ["host/b.ss" "(define x 1)"]])))
  (is (= #{["host/x/a.ss" "host/b.ss"]}
         (edges [["host/x/a.ss" "(load \"../b.ss\")"] ["host/b.ss" "(define x 1)"]])))
  (is (= #{["host/a.ss" "host/b.ss"]}
         (edges [["host/a.ss" "(load \"./b.ss\")"] ["host/b.ss" "(define x 1)"]]))))

(deftest its-own-directory-wins-over-a-same-named-file
  (is (= #{["x/a.ss" "x/rt.ss"]}
         (edges [["x/a.ss" "(load \"rt.ss\")"] ["x/rt.ss" "(define x 1)"] ["y/rt.ss" "(define y 1)"]]))))

(deftest a-load-from-a-subdirectory-analysis-still-resolves
  ;; analyzing host/chez while the sources name paths from the repo root
  (is (= #{["/repo/host/chez/a.ss" "/repo/host/chez/b.ss"]}
         (edges [["/repo/host/chez/a.ss" "(load \"host/chez/b.ss\")"]
                 ["/repo/host/chez/b.ss" "(define x 1)"]]
                {:root "/repo/host/chez"}))))

(deftest an-ambiguous-load-makes-no-edge
  (is (empty? (edges [["a.ss" "(load \"lib/rt.ss\")"]
                      ["x/lib/rt.ss" "(define x 1)"]
                      ["y/lib/rt.ss" "(define y 1)"]]))))

(deftest a-load-outside-the-project-makes-no-edge
  (is (empty? (edges [["a.ss" "(load \"missing.ss\")"]]))))

(deftest a-file-does-not-depend-on-itself
  (is (empty? (edges [["a.ss" "(load \"a.ss\")"]]))))

(deftest a-partial-file-name-does-not-match
  (is (empty? (edges [["a.ss" "(load \"t.ss\")"] ["rt.ss" "(define x 1)"]]))))

(deftest libraries-resolve-across-files
  (is (= #{["a.ss" "lib.sls"]}
         (edges [["a.ss" "(import (chezscheme) (only (foo bar) f))"]
                 ["lib.sls" "(library (foo bar) (export f) (import (chezscheme)) (define (f) 1))"]]))))

(deftest coupling-counts-load-edges
  (let [result (core/analyze-files [["a.ss" "(load \"b.ss\") (load \"c.ss\")"]
                                    ["b.ss" "(load \"c.ss\")"]
                                    ["c.ss" "(define x 1)"]])
        by-path (into {} (map (juxt :path identity) (:files result)))]
    (is (= [2 0] ((juxt :efferent-coupling :afferent-coupling) (by-path "a.ss"))))
    (is (= [0 2] ((juxt :efferent-coupling :afferent-coupling) (by-path "c.ss"))))
    (is (= :stable (:instability-band (by-path "c.ss"))))))

(deftest clojure-coupling-is-unchanged-in-a-mixed-project
  (is (= #{["a.clj" "b.clj"] ["c.ss" "d.ss"]}
         (edges [["a.clj" "(ns a (:require [b]))"] ["b.clj" "(ns b)"]
                 ["c.ss" "(load \"d.ss\")"] ["d.ss" "(define x 1)"]]))))
