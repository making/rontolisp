(defn ^:dynamic slow [n] (* n 2))
(println (binding [slow (memoize slow)] (slow 21)))
