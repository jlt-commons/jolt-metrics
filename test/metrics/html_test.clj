(ns metrics.html-test
  "The HTML report: one self-contained page, escaped, readable without its script."
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is]]
            [metrics.core :as core]
            [metrics.html :as html]))

(defn- page [files] (html/render (core/analyze-files files)))

(deftest it-is-one-self-contained-document
  (let [p (page [["a.clj" "(ns a) (defn f [] 1)"]])]
    (is (str/starts-with? p "<!doctype html>"))
    (is (str/includes? p "<style>"))
    (is (str/includes? p "<script type=\"application/json\" id=\"metrics-data\">"))
    (is (not-any? #(str/includes? p %) ["http://" "https://" "<link" " src=" "@import" "url("]))))

(deftest it-has-the-markdown-sections
  (let [p (page [["a.clj" "(ns a) (defn f [] 1)"]])]
    (doseq [id ["summary" "queue" "cleanup" "files" "architecture" "thresholds"]]
      (is (str/includes? p (str "<section id=\"" id "\"")) id))
    (is (not (str/includes? p "<section id=\"hotspots\"")))))

(deftest names-are-escaped
  (let [p (page [["a.clj" "(ns a) (defn <x> [] 1) (defn a&b [] 2)"]])]
    (is (str/includes? p "&lt;x&gt;"))
    (is (str/includes? p "a&amp;b"))
    (is (not (str/includes? p "<x>")))))

(deftest embedded-json-cannot-close-its-script
  (let [p (page [["a.clj" "(ns a) (defn <!-- [] 1) (defn </script> [] 2)"]])]
    (is (not (str/includes? p "<!--")))
    (is (= 2 (count (re-seq #"</script>" p))))))

(deftest tables-are-complete-without-js
  (let [p (page [["a.clj" "(ns a)"] ["b.clj" "(ns b (:require [a]))"]])]
    (is (str/includes? p "<td>a.clj</td>"))
    (is (str/includes? p "<td>b.clj</td>"))))

(deftest an-empty-result-renders
  (is (str/includes? (html/render (core/analyze-files [])) "</html>")))

(deftest top-caps-the-queue
  (let [big #(str "(defn " % " [x]\n" (apply str (repeat 60 "  (g x)\n")) "  x)")
        p (html/render (core/analyze-files [["a.clj" (str "(ns a)\n" (big "f") "\n" (big "g"))]]) {:top 1})]
    (is (= 1 (count (re-seq #"<li data-severity=" p))))
    (is (str/includes? p "and 1 more"))))

(deftest it-follows-the-system-theme-and-can-be-switched
  (let [p (page [["a.clj" "(ns a)"]])]
    (is (str/includes? p "prefers-color-scheme: dark"))
    (is (str/includes? p ":root[data-theme=\"dark\"]"))
    (is (str/includes? p ":root:not([data-theme=\"light\"])"))
    (is (str/includes? p "id=\"theme-toggle\""))))

(deftest one-is-singular
  (let [body (apply str (repeat 60 "  (g x)\n"))
        p (page [["a.clj" (str "(ns a)\n(defn f [x]\n" body "  x)")]])]
    (is (str/includes? p "1 param,"))
    (is (not (str/includes? p "1 params")))))
