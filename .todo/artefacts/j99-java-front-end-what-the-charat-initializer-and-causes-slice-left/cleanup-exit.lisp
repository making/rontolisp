(defun ci-crf-replaced ()
  (block b
    (unwind-protect
         (block c
           (unwind-protect (return-from b :first)
             (format t "replaces~%")
             (return-from c :second)))
      (format t "outer cleanup~%"))))
(defun ci-crf-again (n)
  (prog ()
     (unwind-protect (if (> n 0) (go out) (return :none))
       (format t "go cleanup~%")
       (if (> n 1) (return :returned) (go out)))
   out
     (return :gone)))
(print (ci-crf-replaced))
(print (list (ci-crf-again 0) (ci-crf-again 1) (ci-crf-again 2)))
