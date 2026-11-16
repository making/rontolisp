(defun run (v n) (let ((s 0) (i 0) (j 0)) (loop while (< i n) do (vector-push-extend i v) (setq j (+ j 1)) (if (= j 1000) (progn (setf (fill-pointer v) 0) (setq j 0))) (setq i (+ i 1))) (length v)))
(print (handler-case (run (make-array 0 :adjustable t :fill-pointer 0) 30000000) (error (e) e)))
