(println (let [f (fn f [n acc] (if (zero? n) acc (recur (dec n) (+ acc n))))] (f 5 0)))
