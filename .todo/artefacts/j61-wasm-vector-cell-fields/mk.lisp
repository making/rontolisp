(defun run (n) (let ((s 0) (i 0)) (loop while (< i n) do (setq s (+ s (length (make-array 4 :initial-element 1)))) (setq i (+ i 1))) s))
(print (handler-case (run 20000000) (error (e) e)))
