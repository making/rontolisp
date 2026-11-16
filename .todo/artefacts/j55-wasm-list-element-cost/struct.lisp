(defstruct box items)
(defun run (b n) (let ((s 0) (i 0)) (loop while (< i n) do (if (box-p b) (setq s (+ s (box-items b)))) (setq i (+ i 1))) s))
(print (handler-case (run (make-box :items 1) 30000000) (error (e) e)))
