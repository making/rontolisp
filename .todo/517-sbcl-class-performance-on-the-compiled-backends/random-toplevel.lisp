(defparameter s 0)
(dotimes (i 10000000) (setq s (+ s (random 1000000))))
(print s)
