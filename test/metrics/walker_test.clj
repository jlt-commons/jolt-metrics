(ns metrics.walker-test
  "Golden examples for what the shared walker counts: every metric reads the same
  code, so code in a let binding, a map or a multi-arity function is seen by all."
  (:require [clojure.test :refer [deftest is testing]]
            [metrics.core :as core]
            [metrics.reader :as r]
            [metrics.cyclomatic :as cyc]
            [metrics.cognitive :as cog]))

(defn- cc [form] (cyc/complexity (r/decision-kinds form)))
(defn- cg [form] (cog/complexity (r/flow-tree form)))
(defn- unit [src] (first (:functions (core/analyze-source "x.clj" src))))
(defn- units [src] (:functions (core/analyze-source "x.clj" src)))
(defn- read1 [src] (first (r/read-forms src)))

;; --- code inside collections is code ------------------------------------

(deftest decisions-in-collections-count
  (is (= 2 (cc '(let [a (if x 1 2)] a))))
  (is (= 2 (cc '{:a (if x 1 2)})))
  (is (= 2 (cc '{(if x 1 2) :a})))
  (is (= 2 (cc '#{(if x 1 2)})))
  (is (= 2 (cc '[(if x 1 2)]))))

(deftest cognitive-sees-collections-too
  (is (= 1 (cg '{:a (if x 1 2)})))
  (is (= 1 (cg '#{(if x 1 2)}))))

(deftest logical-loc-sees-collections
  (is (= 3 (r/form-count '(let [a (f (g 1))] a))))
  (is (= 2 (r/form-count '(f {:a (g 1)}))))
  (is (= 2 (r/form-count '(f #{(g 1)}))))
  (is (= 2 (r/form-count '[(f) (g)]))))

(deftest halstead-sees-collections
  (is (= {:operators ["f" "g"] :operands [":a" "1"]}
         (r/halstead-tokens '(f {:a (g 1)}))))
  (is (= {:operators ["f" "g"] :operands ["1"]}
         (r/halstead-tokens '(f #{(g 1)})))))

(deftest a-computed-head-is-code
  (is (= 2 (cc '((if a f g) x))))
  (is (= 2 (r/form-count '((f) x))))
  (is (= ["f" "g"] (:operators (r/halstead-tokens '((f) (g)))))))

;; --- data is not code ----------------------------------------------------

(deftest quoted-data-is-not-code
  (is (= 1 (cc ''(if a b c))))
  (is (= 0 (cg ''(if a b c))))
  (is (= 1 (r/form-count ''(if a (b) c)))))

(deftest comment-forms-are-not-code
  (is (= 1 (cc '(comment (if a b c)))))
  (is (= 0 (cg '(comment (if a b c)))))
  (is (= 0 (r/form-count '(comment (a))))))

(deftest case-constants-are-not-code
  (is (= 2 (cc '(case op (and or) 1 2))))
  (is (= 1 (cg '(case op (if when) 1 2)))))

;; --- the decision table --------------------------------------------------

(deftest and-or-count-per-operator
  (is (= 1 (cc '(and a))))
  (is (= 2 (cc '(and a b))))
  (is (= 3 (cc '(or a b c)))))

(deftest condp-counts-clauses
  (is (= 4 (cc '(condp = x 1 a 2 b 3 c))))
  (is (= 3 (cc '(condp = x 1 a 2 b c))) "a trailing default is free")
  (is (= 3 (cc '(condp = x 1 :>> f 2 :>> g)))))

(deftest any-truthy-literal-cond-default-is-free
  (is (= 2 (cc '(cond a b :default c))))
  (is (= 2 (cc '(cond a b :otherwise c))))
  (is (= 3 (cc '(cond a b c d)))))

(deftest conditional-threading-counts
  (is (= 3 (cc '(cond-> x a (f) b (g)))))
  (is (= 3 (cc '(cond->> x a (f) b (g))))))

(deftest when-first-is-a-branch
  (is (= 2 (cc '(when-first [a b] a)))))

(deftest comprehension-clauses-count
  (is (= 2 (cc '(for [x xs] x))))
  (is (= 3 (cc '(for [x xs y ys] x))) "each binding is a loop")
  (is (= 3 (cc '(for [x xs :when (p x)] x))))
  (is (= 3 (cc '(doseq [x xs :while (p x)] x))))
  (is (= 2 (cc '(for [x xs :let [y 1]] y))) ":let does not branch"))

(deftest qualified-core-names-count
  (is (= 2 (cc '(clojure.core/when a b))))
  (is (= 1 (cc '(my.ns/when a b))) "someone else's when is just a call"))

;; --- when bodies ---------------------------------------------------------

(deftest when-bodies-are-all-read
  (is (= 3 (cg '(when a (b) (c) (if d 1 2)))) "when +1, the nested if +2")
  (is (= 3 (cg '(when-let [x y] (a) (b) (if d 1 2))))))

(deftest a-when-body-is-not-an-else
  (is (= [:If [:Leaf] [:Seq [:Leaf] [:Seq [:Leaf] [:Leaf]]] [:Leaf]]
         (r/flow-tree '(when a b c)))))

;; --- function shapes ----------------------------------------------------

(deftest multi-arity-reads-every-arity
  (let [f (unit "(defn f ([x] (if x 1 2)) ([x y] (if x y 2)))")]
    (is (= 3 (:cyclomatic f)))
    (is (= 2 (:cognitive f)))
    (is (= 2 (:lloc f)))))

(deftest doc-and-multi-arity-reads-every-arity
  (is (= 3 (:cyclomatic (unit "(defn f \"d\" ([x] (if x 1 2)) ([x y] (if x y 2)))")))))

(deftest attr-map-does-not-leak-params
  (let [plain (unit "(defn f [x] (if x (if x 1 2) 3))")]
    (is (= (:halstead-volume plain)
           (:halstead-volume (unit "(defn f \"d\" {:a 1} [x] (if x (if x 1 2) 3))"))))
    (is (= (:halstead-volume plain)
           (:halstead-volume (unit "(defn f {:a 1} [x] (if x (if x 1 2) 3))"))))))

(deftest every-definition-form-is-a-unit
  (testing "name and kind"
    (is (= [["m :a" :method]] (map (juxt :name :kind) (units "(defmethod m :a [x] (if x 1 2))"))))
    (is (= [["f" :function]] (map (juxt :name :kind) (units "(def f (fn [x] (if x 1 2)))"))))
    (is (= [["f" :function]] (map (juxt :name :kind) (units "(def f \"d\" (fn [x] (if x 1 2)))"))))
    (is (= [["f" :function]] (map (juxt :name :kind) (units "(defonce f (fn [x] (if x 1 2)))"))))
    (is (= [["m" :function]] (map (juxt :name :kind) (units "(defmulti m (fn [x] (if x 1 2)))"))))
    (is (= [["R/m" :method]] (map (juxt :name :kind) (units "(defrecord R [a] P (m [_] (if a 1 2)))"))))
    (is (= [["T/m" :method]] (map (juxt :name :kind) (units "(deftype T [a] P (m [_] (if a 1 2)))"))))
    (is (= [["S/m" :method]] (map (juxt :name :kind) (units "(extend-type S P (m [_] (if 1 1 2)))"))))
    (is (= [["S/m" :method] ["U/n" :method]]
           (map (juxt :name :kind) (units "(extend-protocol P S (m [_] 1) U (n [_] 2))"))))
    (is (= [["t" :test]] (map (juxt :name :kind) (units "(deftest t (is (= 1 1)))"))))
    (is (= [["mac" :macro]] (map (juxt :name :kind) (units "(defmacro mac [x] x)")))))
  (testing "the code inside is measured"
    (is (= 2 (:cyclomatic (unit "(defmethod m :a [x] (if x 1 2))"))))
    (is (= 2 (:cyclomatic (unit "(def f (fn [x] (if x 1 2)))"))))
    (is (= 2 (:cyclomatic (unit "(defrecord R [a] P (m [_] (if a 1 2)))"))))
    (is (= 3 (:cyclomatic (unit "(extend-type S P (m ([_] (if 1 1 2)) ([_ a] (if a 1 2))))")))))
  (testing "plain values are not units"
    (is (empty? (units "(def x 3)")))
    (is (empty? (units "(defprotocol P (m [x] \"doc\"))"))))
  (testing "definitions inside a top-level do"
    (is (= ["f" "g"] (map :name (units "(do (defn f [] 1) (defn g [] 2))"))))))

(deftest definitions-are-not-counted-twice-at-file-level
  (let [file (core/analyze-source "x.clj" "(defmethod m :a [x] (if x 1 2)) (def y (if 1 2 3)) (comment (if a b))")]
    (is (= 3 (:cyclomatic file)) "unit 2 + the def's top-level if; the comment is free")))

(deftest file-cognitive-includes-top-level-forms
  (is (= 2 (:cognitive (core/analyze-source "x.clj" "(defn f [x] (if x 1 2)) (def y (if 1 2 3))")))))

;; --- macros are measured as written --------------------------------------

(deftest syntax-quote-is-read-back
  (is (= '(if x [y (g)] {:a z} #{1})
         (r/desugar (read1 "`(if ~x [~y (g)] {:a ~z} #{1})")))))

(deftest a-macro-template-counts-as-written
  (let [m (unit "(defmacro m [x] `(if ~x (when ~x 1) 2))")]
    (is (= 3 (:cyclomatic m)))
    (is (= 3 (:cognitive m)))
    (is (= 2 (:lloc m)))))

(deftest a-template-splice-is-still-code
  (is (= 2 (:cyclomatic (unit "(defmacro m [& xs] `(when a ~@xs))")))))

(deftest syntax-quoted-core-names-resolve
  (is (= [:if] (r/decision-kinds (r/desugar (read1 "`(when ~x 1)"))))))

;; --- halstead operand identity ------------------------------------------

(deftest token-identity-separates-literals-from-symbols
  (let [ods (:operands (r/halstead-tokens '(f "a" a :a nil)))]
    (is (= 4 (count (distinct ods)))))
  (is (= 1 (count (:operands (r/halstead-tokens (read1 "(f #\"a\")")))))))

;; --- named helpers are units of their own ---------------------------------

(deftest letfn-helpers-are-measured-on-their-own
  (let [[f g h :as all] (units (str "(defn f [x]\n"
                                    "  (letfn [(g [a b] (if a 1 2))\n"
                                    "          (h [c] (when c (g c c)))]\n"
                                    "    (if x (h x) 0)))"))]
    (is (= [["f" :function] ["f/g" :helper] ["f/h" :helper]] (map (juxt :name :kind) all)))
    (testing "the outer function keeps only its own code"
      (is (= [2 1 1 1] ((juxt :cyclomatic :cognitive :nesting :params) f)))
      (is (= 2 (get-in f [:locs :sloc])) "the helpers' lines are theirs"))
    (testing "each helper has its own figures and extent"
      (is (= [2 1 1 2] ((juxt :cyclomatic :cognitive :nesting :params) g)))
      (is (= [2 1 1 1] ((juxt :cyclomatic :cognitive :nesting :params) h)))
      (is (= [2 2 1] [(:line g) (:end-line g) (get-in g [:locs :sloc])]))
      (is (= [3 3] [(:line h) (:end-line h)])))))

(deftest helpers-nest-and-can-sit-in-a-closure
  (is (= [["f" 1] ["f/g" 1] ["f/g/h" 2]]
         (map (juxt :name :cyclomatic)
              (units "(defn f [] (letfn [(g [] (letfn [(h [x] (if x 1 2))] (h 1)))] (g)))"))))
  (is (= [["f" 1] ["f/g" 2]]
         (map (juxt :name :cyclomatic)
              (units "(defn f [] (fn [] (letfn [(g [x] (if x 1 2))] (g 1))))")))))

(deftest a-multi-arity-helper-reads-every-arity
  (let [g (second (units "(defn f [] (letfn [(g ([a] (if a 1 2)) ([a b] (if b a 2)))] (g 1)))"))]
    (is (= ["f/g" 3 2] ((juxt :name :cyclomatic :params) g)))))

(deftest an-anonymous-fn-stays-part-of-its-parent
  (is (= [["f" 2]] (map (juxt :name :cyclomatic) (units "(defn f [xs] (map (fn [x] (if x 1 2)) xs))")))))

(deftest quoted-letfn-is-data
  (is (= ["f"] (map :name (units "(defn f [] '(letfn [(g [] 1)] (g)))")))))

(deftest the-file-total-counts-each-helper
  (is (= 3 (:cyclomatic (core/analyze-source "x.clj" "(defn f [] (letfn [(g [x] (if x 1 2))] (g 1)))")))))
