(defun run (v n) (let ((s 0) (i 0) (j 0)) (loop while (< i n) do (setq s (+ s (fill-pointer v))) (setq j (+ j 1)) (if (= j 1000) (setq j 0)) (setq i (+ i 1))) s))
(print (handler-case (run (make-array 3 :adjustable t :fill-pointer 3) 30000000) (error (e) e)))
