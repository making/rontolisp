(defn ^{:test (fn [] (assert false))} idx [pred coll] (first (keep-indexed (fn [i x] (when (pred x) i)) coll))) (println (idx #{\b} "abc"))
