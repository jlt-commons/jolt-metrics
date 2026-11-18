(ns metrics.deflate
  "DEFLATE, the algorithm of gzip, as a measure of size: how many bytes text
  takes once compressed.  Host interop, kept apart so that metrics.compression,
  which reasons with the sizes, stays pure.")

(defn utf8-size
  "The bytes of text as UTF-8."
  [^String text]
  (count (.getBytes text "UTF-8")))

(defn compressed-size
  "The bytes of text, as UTF-8, once DEFLATE has compressed it at its best level,
  raw: no header or checksum, so the size is the text's alone."
  [^String text]
  (let [in (.getBytes text "UTF-8")
        deflater (doto (java.util.zip.Deflater. 9 true)
                   (.setInput in)
                   (.finish))
        ;; room for the whole output in one go, which is never much over the input
        buf (byte-array (min 65536 (+ 64 (count in))))]
    (loop [n 0]
      (if (.finished deflater)
        n
        (recur (+ n (.deflate deflater buf)))))))
