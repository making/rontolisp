(def game {:x 1})
(defn update-positions [g] (assoc g :x 2))
(println (:x (swap! (atom game) update-positions)))
