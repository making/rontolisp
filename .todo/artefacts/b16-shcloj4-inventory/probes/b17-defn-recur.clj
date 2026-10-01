(defn tg [n] (if (zero? n) :done (recur (dec n)))) (println (tg 3))
