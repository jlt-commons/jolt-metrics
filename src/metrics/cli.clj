(ns metrics.cli
  "Command-line entry point.

    jolt -m metrics.cli <dir>            # print the Markdown report
    jolt -m metrics.cli <dir> out.md     # write it to a file"
  (:require [metrics.core :as core]
            [metrics.report :as report]
            [clojure.string :as str]))

(defn -main [& args]
  (let [dir (or (first args) ".")
        out (second args)
        result (core/analyze-dir dir)
        md (report/render result)]
    (if out
      (do (spit out md)
          (println (str "wrote " out)))
      (println md))))
