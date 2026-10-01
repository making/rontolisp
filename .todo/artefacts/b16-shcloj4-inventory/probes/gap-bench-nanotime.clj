(defmacro bench [expr] `(let [start# (System/nanoTime) result# ~expr] {:result result# :elapsed (- (System/nanoTime) start#)})) (println (:result (bench (+ 1 2))))
