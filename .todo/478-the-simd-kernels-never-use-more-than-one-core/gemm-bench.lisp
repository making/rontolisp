;;;; linalg:dot over two n x n single-float matrices, timed: the --blas vs --simd
;;;; --parallel comparison of .todo/478 phase 3. Prints ms per product, median of 7
;;;; after 3 warm-up rounds, for each n in the list.
(defun bench (n reps)
  (let* ((a (linalg:reshape (linalg:sin (linalg:arange 1 (+ 1 (* n n)) :element-type 'single-float)) (list n n)))
         (b (linalg:reshape (linalg:cos (linalg:arange 1 (+ 1 (* n n)) :element-type 'single-float)) (list n n)))
         (times nil))
    (dotimes (i 3) (linalg:dot a b))
    (dotimes (i 7)
      (let ((t0 (get-internal-real-time)))
        (dotimes (j reps) (linalg:dot a b))
        (push (/ (* 1000.0 (- (get-internal-real-time) t0)) (* reps internal-time-units-per-second)) times)))
    (setf times (sort times #'<))
    (format t "n=~a ~,3f ms/product (check ~a)~%" n (nth 3 times) (aref (linalg:dot a b) 0 0))))
(bench 128 50)
(bench 256 20)
(bench 512 5)
(bench 1024 2)
