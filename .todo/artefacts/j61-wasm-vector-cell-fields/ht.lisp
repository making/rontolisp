(defun run (n) (let ((h (make-hash-table)) (s 0) (i 0)) (loop while (< i n) do (setf (gethash (mod i 1000) h) i) (setq s (+ s (gethash (mod (* i 7) 1000) h 0))) (setq i (+ i 1))) s))
(print (handler-case (run 10000000) (error (e) e)))
