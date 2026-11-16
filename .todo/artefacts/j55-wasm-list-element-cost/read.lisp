(defun run (v n) (let ((s 0) (i 0) (j 0)) (loop while (< i n) do (setq s (aref v j)) (setq j (+ j 1)) (if (= j 1000) (setq j 0)) (setq i (+ i 1))) s))
(print (handler-case (run (make-array 1000 :initial-element 1) 30000000) (error (e) e)))
