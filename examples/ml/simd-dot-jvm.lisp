;;;; The `--simd` demo sized for the JVM backend -- the one backend where
;;;; `examples/ml/simd-dot.lisp` is too short to show anything.
;;;;
;;;; On the JVM, `--simd` compiles `vec:dot` to a jdk.incubator.vector bridge.
;;;; That bridge is ordinary Java until the JIT compiles it down to real CPU
;;;; vector instructions; before that happens, every lane operation is a genuine
;;;; method call, far slower than the plain scalar loop it replaced. A run of a
;;;; few thousand dots is over before the JIT gets there, so on such a run
;;;; `--simd` LOSES. This program shows both sides of that cliff by timing the
;;;; same dot product twice:
;;;;
;;;;   cold: 4000 reps  -- simd-dot.lisp's exact workload, JIT warmup included
;;;;   warm: 100000 reps -- the steady state, which is all that matters to a
;;;;                        long-running process
;;;;
;;;; Compile it both ways and compare the two `warm` lines (the class needs the
;;;; incubator module at runtime):
;;;;
;;;;   rontolisp examples/ml/simd-dot-jvm.lisp -o Dot.class
;;;;   java Dot
;;;;
;;;;   rontolisp examples/ml/simd-dot-jvm.lisp -o Dot.class --simd
;;;;   java --add-modules jdk.incubator.vector Dot
;;;;
;;;; Expected shape: the cold line may well be SLOWER with `--simd` (warmup),
;;;; the warm line clearly faster. How much faster depends on the JVM -- whether
;;;; it turns the Vector API into vector instructions is its decision, not ours
;;;; -- so measure on the JVM you deploy on. See
;;;; doc/en/guides/simd-acceleration.md.
;;;;
;;;; The sum is the same exact integer in every loop and under every flag, for
;;;; the reason simd-dot.lisp explains: every partial sum of squares below 1024
;;;; is exactly representable as a double, in any summation order.
;;;;
;;;; This file is deliberately NOT in examples/examples.yaml: its point is
;;;; elapsed time, it is JVM-specific, and the 104000 interpreter-mode dots take
;;;; about a minute without `--simd`. Run it by hand, as above. For the
;;;; interpreter and WASM backends use simd-dot.lisp, which needs no warmup
;;;; phase.

(defparameter *v* (vec:arange 1024))
(defparameter *cold-reps* 4000)
(defparameter *warm-reps* 100000)

(format t "(vec:dot v v) over ~a doubles~%" (length *v*))

(let ((start (get-internal-real-time))
      (sum 0.0))
  (dotimes (i *cold-reps*) (setq sum (vec:dot *v* *v*)))
  (format t "cold: ~a reps in ~a ms (JIT warmup included), sum = ~a~%"
          *cold-reps* (- (get-internal-real-time) start) (round sum)))

(let ((start (get-internal-real-time))
      (sum 0.0))
  (dotimes (i *warm-reps*) (setq sum (vec:dot *v* *v*)))
  (format t "warm: ~a reps in ~a ms (steady state), sum = ~a~%"
          *warm-reps* (- (get-internal-real-time) start) (round sum)))
