;; The CPU side of phase 4b's crossover: the element-wise `linalg:` members under
;; `--simd`, JIT-warm, at the sizes ElementwiseCrossover.java measures the device at.
;; Pair the two tables column by column -- a member is worth intercepting only where the
;; device column is BELOW this one by a margin that survives warm-up.
;;
;; The data is a linspace over (0.01, 3.0): positive (so `log` and `sqrt` are defined) and
;; varied, which matters for `erf` -- its series iteration count grows with x^2, so a
;; constant-valued array measures one point of a data-dependent cost.
;;
;; Every member is called through a LITERAL call form, never through `funcall`: on the
;; compiled backends the interception is at the call site, so `(funcall #'linalg:exp a)`
;; would measure the scalar defun and quietly report the wrong baseline.
;;
;;   JAR=../../target/rontolisp-0.1.0-SNAPSHOT-exec.jar
;;   java -jar $JAR elementwise-baseline.lisp -o Ew.class --simd    # keep the name path-free
;;   java --add-modules jdk.incubator.vector Ew
(defmacro bench (label n reps form)
  `(progn
     (dotimes (i 20) ,form)
     (let ((best 1.0e30))
       (dotimes (round 3)
         (let ((t0 (get-internal-real-time)))
           (dotimes (i ,reps) ,form)
           (let ((us (/ (* 1000000.0 (- (get-internal-real-time) t0))
                        (* ,reps internal-time-units-per-second))))
             (when (< us best) (setq best us)))))
       (format t "~a n=~d ~,1f us/call~%" ,label ,n best))))

(defun run-f64 (n reps)
  (let ((a (linalg:linspace 0.01 3.0 n))
        (b (linalg:linspace 0.02 2.0 n)))
    (bench "f64 exp" n reps (linalg:exp a))
    (bench "f64 log" n reps (linalg:log a))
    (bench "f64 tanh" n reps (linalg:tanh a))
    (bench "f64 erf" n reps (linalg:erf a))
    (bench "f64 sqrt" n reps (linalg:sqrt a))
    (bench "f64 sin" n reps (linalg:sin a))
    (bench "f64 add" n reps (linalg:add a b))
    (bench "f64 mul" n reps (linalg:mul a b))))

(defun run-f32 (n reps)
  (let ((a (linalg:linspace 0.01 3.0 n :element-type 'single-float))
        (b (linalg:linspace 0.02 2.0 n :element-type 'single-float)))
    (bench "f32 exp" n reps (linalg:exp a))
    (bench "f32 log" n reps (linalg:log a))
    (bench "f32 tanh" n reps (linalg:tanh a))
    (bench "f32 erf" n reps (linalg:erf a))
    (bench "f32 sqrt" n reps (linalg:sqrt a))
    (bench "f32 sin" n reps (linalg:sin a))
    (bench "f32 add" n reps (linalg:add a b))
    (bench "f32 mul" n reps (linalg:mul a b))))

(dolist (n '(4096 16384 65536 262144 1048576 1572864 4194304))
  (let ((reps (if (<= n 262144) 200 20)))
    (run-f64 n reps)
    (run-f32 n reps)))
