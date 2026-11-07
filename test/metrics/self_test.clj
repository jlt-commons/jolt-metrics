(ns metrics.self-test
  "This repository holds itself to its own report: if a change pushes the source
  over a threshold, refactor it or argue the threshold down in metrics.queue."
  (:require [clojure.test :refer [deftest is]]
            [metrics.core :as core]))

(deftest our-own-source-is-under-the-thresholds
  (let [{:keys [queue errors]} (core/analyze-dir "src")]
    (is (empty? errors))
    (is (empty? (map (juxt :name :path :line) queue)))))
