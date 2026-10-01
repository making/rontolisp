(defmacro unless [expr form] (list (quote if) expr nil form)) (println (unless false :ran))
