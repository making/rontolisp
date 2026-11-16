(defun lim (v) (length v))
(defun run (v n) (let ((s 0) (i 0) (j 0) (m (lim v))) (loop while (< i n) do (if (integerp s) (if (< j m) (setq s (+ s 1)))) (setq j (+ j 1)) (if (= j 1000) (setq j 0)) (setq i (+ i 1))) s))
(print (handler-case (run (make-array 2000 :initial-element 1) 30000000) (error (e) e)))
