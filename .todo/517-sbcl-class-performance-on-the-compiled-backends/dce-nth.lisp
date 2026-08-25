(defparameter lst (loop for i from 1 to 1000000 collect i))
(time
  (locally
    (declare (optimize (speed 3) (safety 0)))
    (loop repeat 100000000 do (nth (random 1000000) lst))))
