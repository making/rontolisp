(defun fib (n) (if (< n 2) n (+ (fib (- n 1)) (fib (- n 2)))))
(defun tak (x y z) (if (< y x) (tak (tak (- x 1) y z) (tak (- y 1) z x) (tak (- z 1) x y)) z))
(print (fib 35))
(print (tak 24 16 8))
