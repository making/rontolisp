;;;; rontolisp examples/ml/gpu-matmul.lisp                # 14846 ms
;;;; rontolisp examples/ml/gpu-matmul.lisp --simd         #  2.54 ms  CPU lanes
;;;; rontolisp examples/ml/gpu-matmul.lisp --gpu --simd   #  0.24 ms  Metal
;;;; rontolisp examples/ml/gpu-matmul.lisp --blas --simd  #  0.05 ms  Accelerate
;;;;
;;;; and the same program as a JVM class, 0.17 ms:
;;;; rontolisp examples/ml/gpu-matmul.lisp -o Gpumatmul.class --gpu --simd
;;;; java --add-modules jdk.incubator.vector --enable-native-access=ALL-UNNAMED Gpumatmul

(defparameter *a* (linalg:rand '(256 256) :element-type 'single-float))

(let ((start (get-internal-real-time)) (calls 0))
  (loop
    (linalg:matmul *a* *a*)
    (incf calls)
    (when (> (- (get-internal-real-time) start) 500) (return)))
  (format t "~,2f ms per 256x256 single-float product~%"
          (/ (* 1.0 (- (get-internal-real-time) start)) calls)))
