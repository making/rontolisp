;; a first smoke test of the experimental Clojure front end

(def greeting "hello")

(defn fact [n]
  (if (< n 2) 1 (* n (fact (- n 1)))))

(defn fib [n]
  (loop [a 0 b 1 i 0]
    (if (= i n) a (recur b (+ a b) (inc i)))))

(defn compose-demo [coll]
  (reduce + 0 (map #(* % %) (filter odd? coll))))

(println (str greeting ", clojure on rontolisp!"))
(println (fact 10))
(println (fib 20))
(println (compose-demo '(1 2 3 4 5 6 7)))
(println (apply max '(3 9 4)))
(println [:a :b "vec"])
(println (count [10 20 30]))
(println (= 1 1) (= 1 2) (nil? nil) (some? 0))
(println (cond
           (= 1 2) :one
           :else :fallback))
(let [x 10
      y (+ x 5)]
  (println (str "x+y=" y)))
(println ((fn [a b] (+ (* a 10) b)) 4 2))
