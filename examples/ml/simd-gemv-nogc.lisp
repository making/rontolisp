;;;; simd-gemv.lisp's inner loop, compiled to a plain linear-memory WASM module
;;;; with `--no-gc` -- the fastest way rontolisp can run a GEMV, and the only
;;;; backend where the SIMD speedup comes with no garbage collector at all.
;;;;
;;;; A `--no-gc` module is a pure-compute reactor: no `_start`, no printing --
;;;; the host calls an exported function and reads the returned integer. So
;;;; where simd-gemv.lisp prints its fingerprint, this one RETURNS it:
;;;;
;;;;   (fingerprint n)  builds the same fixed-seed 256x256 single-float matrix,
;;;;                    runs n steps of  x <- rms-normalize(W x),  and returns
;;;;                    argmax(x) -- which component of the vector is largest.
;;;;
;;;; Each step is one vec:matvec-into (the GEMV) and one vec:dot (the RMS), the
;;;; two kernels an LLM inference engine spends nearly all of its time in. The
;;;; -into kernels matter here more than anywhere: `--no-gc` bump-allocates and
;;;; never frees, so the loop writes into two pre-allocated vectors and the
;;;; whole run allocates exactly three blocks (W, x, y) no matter how many
;;;; steps it takes.
;;;;
;;;; RUN IT BOTH WAYS
;;;; ----------------
;;;;   rontolisp examples/ml/simd-gemv-nogc.lisp -o gemv.wasm --no-gc --optimize
;;;;   wasmtime run --invoke fingerprint gemv.wasm 100
;;;;
;;;;   rontolisp examples/ml/simd-gemv-nogc.lisp -o gemv.wasm --no-gc --simd --optimize
;;;;   wasmtime run --invoke fingerprint gemv.wasm 100
;;;;
;;;; Both print 85 -- the same dominant direction simd-gemv.lisp settles into on
;;;; every other backend -- and the argmax after each of steps 1-10 matches its
;;;; printed (0 14 82 126 14 140 126 79 134 175) too. The scalar build carries
;;;; no SIMD instruction at all (it runs even under `wasmtime -W simd=n -W
;;;; relaxed-simd=n`); the `--simd` build runs the same loop as f32x4 lanes.
;;;; The time difference is the point: measured on an Apple M4 at 20000 steps,
;;;; the scalar module takes ~600 ms and the `--simd` one ~120 ms (5x).
;;;;
;;;; DETERMINISM
;;;; -----------
;;;; The same Lehmer generator as simd-gemv.lisp, threaded through a local
;;;; instead of a global (`--no-gc` has no globals), so the matrix is
;;;; bit-identical to the one every other backend builds. argmax is an integer
;;;; fingerprint of every multiply-add, yet unmoved by the last-bit differences
;;;; lane-order (or f32-throughout) summation introduces.

;;; Which component of the vector is the largest.
(defun argmax (v)
  (let ((best 0))
    (dotimes (i (length v))
      (when (> (aref v i) (aref v best)) (setq best i)))
    best))

;;; n steps of x <- rms-normalize(W x) over the fixed-seed matrix; returns
;;; argmax(x). W is a rank-2 packed single-float matrix built with make-array;
;;; the LCG state is a plain local: s <- (75 s + 74) mod 65537, and each draw
;;; maps to a single-float in [-1, 1) exactly as simd-gemv.lisp's lcg-uniform.
(defun fingerprint (n)
  (let ((dim 256)
        (eps 0.00001)
        (s 7))
    (let ((w (make-array (list dim dim) :element-type 'single-float))
          (x (vec:zeros dim 'single-float))
          (y (vec:zeros dim 'single-float)))
      (dotimes (i dim)
        (dotimes (j dim)
          (setq s (mod (+ (* s 75) 74) 65537))
          (setf (aref w i j) (- (/ (mod s 2048) 1024.0) 1.0))))
      (dotimes (i dim)
        (setq s (mod (+ (* s 75) 74) 65537))
        (setf (aref x i) (- (/ (mod s 2048) 1024.0) 1.0)))
      (dotimes (k n)
        (vec:matvec-into y w x)
        (vec:scale-into x y (/ 1.0 (sqrt (+ (/ (vec:dot y y) dim) eps)))))
      (argmax x))))

(rontolisp:wasm-export 'fingerprint :params '(:int) :returns :int)
