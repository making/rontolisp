(time
  (locally
    (declare (optimize (speed 3) (safety 0)))
    (loop repeat 100000000 do (random 1000000))))
