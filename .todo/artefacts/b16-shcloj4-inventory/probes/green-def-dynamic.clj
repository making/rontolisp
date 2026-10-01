(def ^:dynamic s (fn [n] (* n 2)))
(println (binding [s (memoize s)] (s 21)))
