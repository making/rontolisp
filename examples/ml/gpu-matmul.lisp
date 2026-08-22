;;;; rontolisp examples/ml/gpu-matmul.lisp               # 14846 ms
;;;; rontolisp examples/ml/gpu-matmul.lisp --simd        #  2.54 ms
;;;; rontolisp examples/ml/gpu-matmul.lisp --gpu --simd  #  0.24 ms

(defparameter *a* (linalg:rand '(256 256) :element-type 'single-float))

(let ((start (get-internal-real-time)) (calls 0))
  (loop
    (linalg:matmul *a* *a*)
    (incf calls)
    (when (> (- (get-internal-real-time) start) 500) (return)))
  (format t "~,2f ms per 256x256 single-float product~%"
          (/ (* 1.0 (- (get-internal-real-time) start)) calls)))
