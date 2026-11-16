(defun run (v n) (let ((s 0) (i 0)) (loop while (< i n) do (setq s (+ s (length v))) (setq i (+ i 1))) s))
(print (handler-case (run (make-array 3 :initial-element 1 :adjustable t :fill-pointer 3) 30000000) (error (e) e)))
