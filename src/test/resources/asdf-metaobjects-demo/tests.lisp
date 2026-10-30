(defun run-demo-tests (name test-op-p)
  (print (concatenate 'string "testing " name))
  (print test-op-p)
  (print (+ (one-fn) (two-fn))))
