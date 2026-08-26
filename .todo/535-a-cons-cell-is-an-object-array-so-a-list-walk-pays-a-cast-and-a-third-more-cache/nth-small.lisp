(defparameter lst (loop for i from 1 to 100 collect i))
(defparameter s 0)
(dotimes (i 10000000) (setq s (+ s (nth 99 lst))))
(print s)
