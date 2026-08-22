;;;; The CPU column for MatvecCrossover.java: one vec:matvec per shape, us per call, JIT-warm,
;;;; under --simd on the JVM class output. Single float first (the width llama2 runs), then
;;;; double. The shapes are MatvecCrossover's, llama2 stories15M's among them.
;;;;
;;;;   JAR=../../target/rontolisp-0.1.0-SNAPSHOT-exec.jar
;;;;   java -jar $JAR matvec-baseline.lisp -o Mv.class --simd
;;;;   java --add-modules jdk.incubator.vector Mv

(defun bench (rows cols type)
  (let* ((w
          (linalg:reshape
           (linalg:sin (linalg:arange 0 (* rows cols) :element-type type))
           (list rows cols)))
         (x (linalg:cos (linalg:arange 0 cols :element-type type)))
         (warm (if (> (* rows cols) 1000000) 30 300))
         (reps
          (if (> (* rows cols) 1000000)
              30
              (if (> (* rows cols) 200000) 200 2000)))
         (best 1e30))
    (dotimes (i warm) (vec:matvec w x))
    (dotimes (round 3)
      (let ((t0 (get-internal-real-time)))
        (dotimes (i reps) (vec:matvec w x))
        (let ((us (/ (* 1000.0 (- (get-internal-real-time) t0)) reps)))
          (when (< us best) (setq best us)))))
    (format t "~a x ~a ~a: ~,1f us/call~%" rows cols type best)))

(dolist (type '(single-float double-float))
  (dolist (shape
           '((64 64) (128 128) (192 192) (256 256) (288 288) (384 384) (512 512)
             (768 288) (288 768) (768 768) (1024 1024) (1448 1448) (1536 1536) (2048 2048)
             (32000 288)
             (256 48) (48 256) (4096 4096)))
    (bench (first shape) (second shape) type)))
