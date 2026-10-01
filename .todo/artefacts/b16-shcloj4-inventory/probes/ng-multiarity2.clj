(defprotocol Q (m [x] [x y]))
(defrecord R [a])
(extend-protocol Q R (m ([x] (:a x)) ([x y] y)))
(println (m (->R 5)))
