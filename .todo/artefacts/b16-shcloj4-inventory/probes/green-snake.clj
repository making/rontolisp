(defn add-points [& pts] (apply vector (apply map + pts))) (println (add-points [1 1] [2 3]))
