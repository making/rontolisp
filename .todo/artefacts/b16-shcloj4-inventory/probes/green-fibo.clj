(defn fibo [] (map first (iterate (fn [[a b]] [b (+ a b)]) [0N 1N])))
(println (apply vector (take 8 (fibo))))
