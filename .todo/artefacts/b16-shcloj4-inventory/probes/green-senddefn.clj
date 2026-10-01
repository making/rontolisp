(def trials (agent 3))
(defn run-sim [n] (* n 2))
(send trials run-sim)
(println @trials)
