(defn square [x] (* x x)) (defn squares-seq [n] (vec (map square (range n)))) (println (squares-seq 4))
