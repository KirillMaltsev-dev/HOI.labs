(ns lab-c2.core-test
  (:require [clojure.test :refer [deftest is run-tests]]
            [lab-c2.core :refer [primes]]))

(deftest first-primes
  (is (= [2 3 5 7 11 13 17 19 23 29 31 37 41 43 47
          53 59 61 67 71 73 79 83 89 97 101 103 107 109 113]
         (take 30 primes))))

(deftest thousandth-prime
  (is (= 7919 (nth primes 999))))

(defn -main [& _]
  (let [{:keys [fail error]} (run-tests 'lab-c2.core-test)]
    (when (pos? (+ fail error))
      (System/exit 1))))
