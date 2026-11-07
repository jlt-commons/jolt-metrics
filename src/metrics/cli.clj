(ns metrics.cli
  "Command-line entry point.

    jolt -m metrics.cli <dir>            # print the Markdown report
    jolt -m metrics.cli <dir> out.md     # write it to a file

  Exits 2 when <dir> does not exist.  A file that cannot be read does not fail
  the run: the report lists it under `Files not analyzed`."
  (:require [metrics.core :as core]
            [metrics.report :as report]
            [jolt.fs :as fs]))

(defn run
  "Do the work of -main; returns {:exit n, :out text, :err text}."
  [args]
  (let [dir (or (first args) ".")
        out (second args)]
    (if-not (fs/directory? dir)
      {:exit 2 :err (str "not a directory: " dir)}
      (let [md (report/render (core/analyze-dir dir))]
        (if out
          (do (spit out md)
              {:exit 0 :out (str "wrote " out)})
          {:exit 0 :out md})))))

(defn -main [& args]
  (let [{:keys [exit out err]} (run args)]
    (when out (println out))
    (when err (binding [*out* *err*] (println err)))
    (flush)
    (System/exit exit)))
