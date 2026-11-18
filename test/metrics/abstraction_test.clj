(ns metrics.abstraction-test
  "Behaviour tests for abstraction search: encoding forms as terms, saturating
  with the sugar rules, anti-unifying e-classes and scoring what is found."
  (:require [clojure.test :refer [deftest is testing]]
            [metrics.abstraction :as ab]
            [metrics.egraph :as eg]))

(defn- unit [n params & forms]
  {:path "a.clj" :name n :line 1 :param-vectors [params] :body (vec forms)})

(deftest encodes-locals-strings-and-literals
  (is (= [[:list 3] [[:sym "str"]] [[:local "x"]] [[:str "hi"]]]
         (ab/encode '#{x} '(str x "hi"))))
  (is (= [[:list 2] [[:sym "inc"]] [[:lit "1"]]]
         (ab/encode #{} '(clojure.core/inc 1))))
  (is (= [[:list 2] [[:sym "fn"]] [[:vec 0]]]
         (ab/encode #{} '(fn* []))) "fn* reads as fn"))

(deftest different-strings-are-parameters
  (let [units [(unit "a" '[] '(section "Hotspots" (render-table :hot (rows 1) [:p "none"])))
               (unit "b" '[] '(section "Unusual" (render-table :odd (rows 2) [:p "none"])))
               (unit "c" '[] '(section "Queue" (render-table :queue (rows 3) [:p "none"])))]
        top (first (ab/opportunities units {:min-size 6}))]
    (is (= '(section ?a (render-table ?b (rows ?c) [:p "none"])) (:pattern top)))))

(deftest one-code-shape-is-reported-once
  ;; its generalizations match the same code, so once it is taken they save nothing
  (let [units (for [[n k] [["a" 'x] ["b" 'y] ["c" 'z] ["d" 'w]]]
                (unit n '[f] (list 'and (list 'seq? 'f) (list '= (list 'quote k) (list 'first 'f)))))
        found (ab/opportunities units {:min-size 6})]
    (is (= 1 (count found)))
    (is (= 4 (:uses (first found))))))

(defn- same-class? [forms a b]
  (let [[g ids] (reduce (fn [[g ids] f]
                          (let [[g id] (eg/add-term g (ab/encode '#{x y} f))]
                            [g (conj ids id)]))
                        [eg/empty-graph []] forms)
        g (ab/saturate g {})]
    (= (eg/find g (ids a)) (eg/find g (ids b)))))

(deftest sugar-rules-make-equal-classes
  (testing "thread-first"
    (is (same-class? ['(-> x (f 1) g) '(g (f x 1))] 0 1)))
  (testing "thread-last"
    (is (same-class? ['(->> x (map f) (filter p)) '(filter p (map f x))] 0 1)))
  (testing "when is a one-armed if"
    (is (same-class? ['(when x (f y)) '(if x (f y))] 0 1)))
  (testing "when-not and if-not negate"
    (is (same-class? ['(when-not x (f y)) '(if (not x) (f y))] 0 1))
    (is (same-class? ['(if-not x 1 2) '(if (not x) 1 2)] 0 1)))
  (testing "an explicit nil else"
    (is (same-class? ['(if x (f y) nil) '(if x (f y))] 0 1))))

(deftest anti-unification-reuses-a-variable-for-a-repeated-pair
  ;; Plotkin: the same pair of subterms generalizes to the same variable
  (let [[g a] (eg/add-term eg/empty-graph (ab/encode '#{a} '(let [a (f 1)] (g a a))))
        [g b] (eg/add-term g (ab/encode '#{b} '(let [b (f 2)] (g b b))))
        ps (ab/anti-unify g a b {})]
    (is (some #{'(let [?a (f ?b)] (g ?a ?a))} (map #(ab/render g %) ps)))))

(deftest a-local-never-stays-concrete
  (let [[g a] (eg/add-term eg/empty-graph (ab/encode '#{x} '(inc x)))
        ps (ab/anti-unify g a a {})]
    (is (= ['(inc ?a)] (map #(ab/render g %) ps)))))

(def ^:private shared
  "Three definitions that build the same map from different inputs."
  [(unit "a" '[m] '(assoc m :status (compute-status (:x m) :ready) :updated (now)))
   (unit "b" '[r] '(assoc r :status (compute-status (:y r) :ready) :updated (now)))
   (unit "c" '[q] '(assoc q :status (compute-status (:z q) :ready) :updated (now)))])

(deftest finds-a-shared-abstraction
  (let [[top] (ab/opportunities shared {:min-size 6})]
    (is (= 3 (:uses top)))
    ;; the least general generalization: the keyword lookup stays a call
    (is (= '(assoc ?a :status (compute-status (?b ?a) :ready) :updated (now)) (:pattern top)))
    (is (= 2 (:arity top)))
    (is (pos? (:saving top)))
    (is (= #{"a" "b" "c"} (set (map :name (:sites top)))))))

(deftest finds-it-through-the-sugar
  (let [units [(unit "a" '[m] '(-> m (assoc :status (compute-status (:x m) :ready)) (assoc :updated (now))))
               (unit "b" '[r] '(assoc (assoc r :status (compute-status (:y r) :ready)) :updated (now)))]
        found (ab/opportunities units {:min-size 6})]
    (is (some #(and (= 2 (:uses %)) (= #{"a" "b"} (set (map :name (:sites %))))) found))))

(deftest binders-are-not-parameters
  (let [units [(unit "a" '[] '(let [v (load-it 1)] (when v (save v :fast))))
               (unit "b" '[] '(let [w (load-it 2)] (when w (save w :fast))))]
        top (first (ab/opportunities units {:min-size 4}))]
    (is (= '(let [?a (load-it ?b)] (when ?a (save ?a :fast))) (:pattern top)))
    (is (= 1 (:arity top)))))

(deftest one-occurrence-is-no-opportunity
  (is (empty? (ab/opportunities [(first shared)] {:min-size 6}))))

(deftest reports-the-sugar-as-written
  (let [units (for [n ["a" "b" "c"]]
                (unit n '[u x] (list 'when-not '(skip? u :lens) (list 'compute (symbol n) 'x 1 2))))
        top (first (ab/opportunities units {:min-size 6}))]
    (is (= '(when-not (skip? ?a :lens) (compute ?b ?c 1 2)) (:pattern top)))))

(deftest a-binding-vector-is-never-a-variable
  (let [units (for [[n bs] [["a" '[x 1]] ["b" '[y 2 z 3]] ["c" '[w (f 4)]]]]
                (unit n '[] (list 'let bs '[{:loc :here :range (meta (node :here))}])))
        found (ab/opportunities units {:min-size 4})]
    (is (not-any? #(and (seq? (:pattern %)) (= 'let (first (:pattern %))) (symbol? (second (:pattern %))))
                  found))))

(deftest a-pair-gives-the-same-patterns-whatever-came-before
  ;; the memo is shared by every pair, run in parallel: a pair first met deep
  ;; inside another, with little depth left, must not cut it short later
  (let [enc #(ab/encode #{} %)
        [g a] (eg/add-term eg/empty-graph (enc '(w (f (g (h (i 1)))))))
        [g b] (eg/add-term g (enc '(w (f (g (h (i 2)))))))
        [g x] (eg/add-term g (enc '(f (g (h (i 1))))))
        [g y] (eg/add-term g (enc '(f (g (h (i 2))))))
        context #'ab/context
        au #'ab/anti-unify*
        fresh (au (context g {:depth 3}) x y)
        ctx (context g {:depth 3})
        _ (au ctx a b)]
    (is (= (map #(ab/render g %) fresh) (map #(ab/render g %) (au ctx x y))))))

(deftest every-use-is-a-site
  ;; c's if-not is smaller as written than the pattern it matches through its
  ;; expansion, so it must not be too small to note where it occurs
  (let [units [(unit "a" '[x] '(if (not (p x 2)) (a 1 2) (b 2 3)))
               (unit "b" '[y] '(if (not (p y 2)) (a 1 2) (b 2 3)))
               (unit "c" '[z] '(if-not (p z 2) (a 1 2) (b 2 3)))]
        found (ab/opportunities units {:min-size 15})]
    (is (seq found))
    (doseq [o found]
      (is (= (:uses o) (count (:sites o))) (pr-str o)))))
