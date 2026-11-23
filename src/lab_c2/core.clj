(ns lab-c2.core)

(defn- sieve [candidate next-multiples]
  (lazy-seq
    (loop [candidate candidate
           next-multiples next-multiples]
      (if-let [factors (get next-multiples candidate)]
        (recur (inc' candidate)
               (reduce (fn [schedule prime]
                         (update schedule (+' candidate prime) (fnil conj []) prime))
                       (dissoc next-multiples candidate)
                       factors))
        ;; Smaller multiples already have a smaller prime factor
        (cons candidate
              (sieve (inc' candidate)
                     (assoc next-multiples (*' candidate candidate) [candidate])))))))

(def primes
  "An infinite lazy sequence of prime numbers"
  (sieve 2 {}))
