(defun wr (v j x) x)
(defun run (v n) (let ((i 0) (j 0)) (loop while (< i n) do (wr v j i) (setq j (+ j 1)) (if (= j 1000) (setq j 0)) (setq i (+ i 1))) (aref v 5)))
(print (handler-case (run (make-array 1000 :initial-element 1) 30000000) (error (e) e)))
