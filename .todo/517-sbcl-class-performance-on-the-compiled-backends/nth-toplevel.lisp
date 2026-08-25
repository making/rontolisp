(defparameter lst (loop for i from 1 to 1000 collect i))
(defparameter s 0)
(dotimes (i 1000000) (setq s (+ s (nth 999 lst))))
(print s)
