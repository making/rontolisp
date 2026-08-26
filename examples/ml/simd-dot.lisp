;;;; The smallest program that shows what `--simd` does.
;;;;
;;;; One kernel, `vec:dot`, over one packed vector of 1024 doubles, four thousand
;;;; times. Nothing else. Run it twice:
;;;;
;;;;   rontolisp examples/ml/simd-dot.lisp
;;;;   rontolisp examples/ml/simd-dot.lisp --simd
;;;;
;;;;   rontolisp examples/ml/simd-dot.lisp -o dot.wasm        && wasmtime run dot.wasm
;;;;   rontolisp examples/ml/simd-dot.lisp -o dot.wasm --simd && wasmtime run dot.wasm
;;;;
;;;; The sum must not change. The elapsed time should. Measured on an Apple M4:
;;;;
;;;;   interpreter  2.59 s  -> 2.3 ms   with --simd   (1100x)
;;;;   wasm-GC       273 ms -> 2.4 ms   with --simd   (115x)
;;;;
;;;; (`-o Prog.class` finishes this in a few tens of milliseconds either way: too
;;;; short for a JIT to warm up. The JVM backend also has a wrinkle worth reading
;;;; about before you rely on it -- see doc/en/guides/simd-acceleration.md.)
;;;;
;;;; WHY THE ANSWER CANNOT CHANGE
;;;; ---------------------------
;;;; The vector holds 0.0, 1.0, ... 1023.0, so its dot product with itself is the
;;;; sum of the squares below 1024 -- an exact integer, and every partial sum on
;;;; the way there is exactly representable as a double. Folding that sum two
;;;; lanes at a time, which is what `--simd` does, therefore lands on the very
;;;; same value, bit for bit. That is the contract: acceleration never changes an
;;;; answer. (Over inexact inputs a reduction may differ in the last bit, because
;;;; the lanes add in a different order.)
;;;;
;;;; For matrices -- and for where a real LLM inference engine spends its time --
;;;; see simd-gemv.lisp.

(defparameter *v* (vec:arange 1024))
(defparameter *reps* 4000)

(format t "(vec:dot v v) over ~a doubles, ~a times = ~a multiply-adds~%"
        (length *v*) *reps* (* (length *v*) *reps*))

(let ((start (get-internal-real-time)) (sum 0.0))
  (dotimes (i *reps*) (setq sum (vec:dot *v* *v*)))
  (format t "sum of squares below 1024 = ~a~%" (round sum))
  (format t "elapsed: ~a ms~%" (- (get-internal-real-time) start)))
