;;;; rontolisp examples/ml/gpu-matmul.lisp                # 14846 ms
;;;; rontolisp examples/ml/gpu-matmul.lisp --simd         #  2.54 ms  CPU lanes
;;;; rontolisp examples/ml/gpu-matmul.lisp --gpu --simd   #  0.24 ms  Metal
;;;; rontolisp examples/ml/gpu-matmul.lisp --blas --simd  #  0.05 ms  Accelerate
;;;;
;;;; The size is the program's own argument -- after `--`, where the compiler's
;;;; options end and the program's begin -- and defaults to 256:
;;;;
;;;; rontolisp examples/ml/gpu-matmul.lisp --gpu --simd -- 2048   #  4.00 ms
;;;; rontolisp examples/ml/gpu-matmul.lisp --blas --simd -- 2048  #  8.72 ms
;;;;
;;;; and the same program as a JVM class, 0.17 ms (a compiled artifact takes the
;;;; argument straight after itself, with no separator to get past):
;;;; rontolisp examples/ml/gpu-matmul.lisp -o Gpumatmul.class --gpu --simd
;;;; java --add-modules jdk.incubator.vector --enable-native-access=ALL-UNNAMED Gpumatmul 2048

(defparameter *n*
  (parse-integer (or (first (uiop:command-line-arguments)) "256")))

(defparameter *a* (linalg:rand (list *n* *n*) :element-type 'single-float))

(let ((start (get-internal-real-time)) (calls 0))
  (loop
    (linalg:matmul *a* *a*)
    (incf calls)
    (when (> (- (get-internal-real-time) start) 500) (return)))
  (format t "~,2f ms per ~ax~a single-float product~%"
          (/ (* 1.0 (- (get-internal-real-time) start)) calls) *n* *n*))
