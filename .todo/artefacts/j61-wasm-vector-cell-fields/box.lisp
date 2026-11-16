(defun mk (i) (let ((c i)) (lambda () (setq c (+ c 1)))))
(defun run (n) (let ((s 0) (i 0)) (loop while (< i n) do (setq s (+ s (funcall (mk i)))) (setq i (+ i 1))) s))
(print (handler-case (run 20000000) (error (e) e)))
