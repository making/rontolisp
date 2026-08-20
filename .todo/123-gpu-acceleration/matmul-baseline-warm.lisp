;; The CPU side of the --gpu crossover, JIT-WARM and at the sizes the threshold is
;; decided by. matmul-baseline.lisp beside this one is the original: 3 warm-up iterations
;; and 20 reps, which over-reports n<=64 by about 10x on a JVM that has not yet compiled
;; the kernel -- and n<=64 is exactly where am.ik.gpu's worth() threshold lives. Same
;; program otherwise; the numbers this one prints are the ones .kb/gpu.md quotes.
(defun bench (n etype label reps)
  (let* ((a (linalg:add (linalg:ones (list n n) :element-type etype) 0.5))
         (b (linalg:add (linalg:ones (list n n) :element-type etype) 0.25)))
    (dotimes (i 200) (linalg:matmul a b))
    (let ((t0 (get-internal-real-time)))
      (dotimes (i reps) (linalg:matmul a b))
      (format t "n=~d ~a ~,2f us/call~%" n label
              (/ (* 1000000.0 (- (get-internal-real-time) t0))
                 (* reps internal-time-units-per-second))))))
(dolist (n '(16 24 32 40 48 56 64 96 128))
  (bench n nil "f64" 4000)
  (bench n 'single-float "f32" 4000))
