(ns lab-c1.core)

(defn valid-strings
  "Returns all length-n strings without equal adjacent symbols"
  [alphabet n]
  (reduce (fn [prefixes _]
            (reduce (fn [extended ^String prefix]
                      (into extended
                            (map #(str prefix %)
                                 (remove #(.endsWith prefix %) alphabet))))
                    []
                    prefixes))
          [""]
          (.repeat " " n)))
