;;;; rontolisp examples/ml/gpu-matmul.lisp               # 15448 ms
;;;; rontolisp examples/ml/gpu-matmul.lisp --simd        #  2.49 ms
;;;; rontolisp examples/ml/gpu-matmul.lisp --gpu --simd  #  0.24 ms

(defparameter *a* (linalg:zeros '(256 256) :element-type 'single-float))

(dotimes (i (* 256 256)) (setf (row-major-aref *a* i) (mod i 8)))

(let ((start (get-internal-real-time)) (calls 0) (c nil))
  (loop
    (setq c (linalg:matmul *a* *a*))
    (incf calls)
    (when (> (- (get-internal-real-time) start) 500) (return)))
  (format t "trace ~a, ~,2f ms per 256x256 single-float product~%"
          (round (linalg:trace c))
          (/ (* 1.0 (- (get-internal-real-time) start)) calls)))
