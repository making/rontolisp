;;;; The two kernels `--simd` accelerates, and the two an LLM inference engine
;;;; spends nearly all of its time in:
;;;;
;;;;   (vec:matvec w x)   y = W x, a matrix times a vector  -- one dot per row
;;;;   (vec:dot a b)      the sum of the products           -- one reduction
;;;;
;;;; Autoregressive decoding runs one token at a time, so every weight matrix in
;;;; a transformer is multiplied by a *vector*, never by another matrix: it is
;;;; all GEMV. And a GEMV is just a dot product per row, which is exactly the
;;;; shape a CPU's vector unit multiplies four (or two) elements at a time.
;;;;
;;;; So this program does nothing but that, a hundred times over: project a
;;;; vector through a random matrix, rescale it to unit root-mean-square, repeat.
;;;; The rescaling is RMSNorm with its gain vector dropped -- `vec:dot` of a
;;;; vector with itself is the sum of its squares -- and it is what keeps the
;;;; numbers from growing without bound, so the loop can run as long as we like.
;;;;
;;;; RUN IT BOTH WAYS
;;;; ----------------
;;;;   rontolisp examples/ml/simd-gemv.lisp                                    # scalar
;;;;   rontolisp examples/ml/simd-gemv.lisp --simd                             # Vector API
;;;;
;;;;   rontolisp examples/ml/simd-gemv.lisp -o gemv.wasm        && wasmtime run -W gc gemv.wasm
;;;;   rontolisp examples/ml/simd-gemv.lisp -o gemv.wasm --simd && wasmtime run -W gc gemv.wasm
;;;;
;;;; The printed indices must not change. The elapsed time should. Measured on an
;;;; Apple M4:
;;;;
;;;;   wasm-GC      467 ms  ->  3.9 ms   with --simd   (120x -- native f32x4)
;;;;   interpreter  4.67 s  -> 0.68 s    with --simd   (6.9x, the native binary)
;;;;
;;;; The JVM backend is the one to be careful with, and this example is the worst
;;;; case for it. `--simd` compiles to a jdk.incubator.vector bridge, and whether
;;;; that bridge becomes real CPU instructions is decided by the JVM that runs the
;;;; class, not by us; where it does not, each lane is emulated and `--simd` ends
;;;; up slower than not passing it. A single-float GEMV is the shape most exposed
;;;; to this, because it widens every f32 lane to f64 before accumulating. On the
;;;; JVMs measured here the result ranged from a 4x speedup to a 20x slowdown --
;;;; so measure on the JVM you deploy on. See doc/en/guides/simd-acceleration.md.
;;;;
;;;; A hundred steps is also too short for a JIT to warm up; raise *steps* first.
;;;;
;;;; DETERMINISM
;;;; -----------
;;;; The matrix comes from a fixed-seed generator, so it is the same on every
;;;; backend and every run. Only INTEGERS are printed: the WASM backend rounds
;;;; floats to about seven significant digits when printing, so a float would not
;;;; compare across backends. What is printed instead is `argmax` -- which
;;;; component of the vector is the largest -- an integer that depends on every
;;;; multiply-add that produced the vector, yet is unmoved by the last-bit
;;;; differences that reordering a sum into vector lanes introduces.

;;; --- size ------------------------------------------------------------------
;;; A row must hold at least 128 elements. Below that the interpreter and JVM
;;; vector kernels fall back to a scalar loop, because setting up the vector
;;; registers would cost more than it saves. (wasm-GC has no such threshold.)
(defparameter *dim* 256)
(defparameter *steps* 100)
(defparameter *eps* 0.00001)

;;; --- deterministic pseudo-random numbers -------------------------------------
;;; A Lehmer generator: every intermediate stays below 2^23, which fits the WASM
;;; backend's integer range, so the stream is identical on all backends.
(defvar *lcg-state* 7)

(defun lcg-next ()
  (setq *lcg-state* (mod (+ (* *lcg-state* 75) 74) 65537))
  *lcg-state*)

;;; a single-float in [-1, 1)
(defun lcg-uniform () (- (/ (mod (lcg-next) 2048) 1024.0) 1.0))

;;; --- the packed float arrays -------------------------------------------------
;;; `:element-type 'single-float` is what makes these packed (unboxed) arrays --
;;; the representation `--simd` needs, and the one `vec:` operates on. A rank-2
;;; array is the matrix; a rank-1 array is the vector.
(defun random-matrix (rows cols)
  (let ((m (make-array (list rows cols) :element-type 'single-float)))
    (dotimes (i rows m) (dotimes (j cols) (setf (aref m i j) (lcg-uniform))))))

(defun random-vector (n)
  (let ((v (vec:zeros n 'single-float)))
    (dotimes (i n v) (setf (aref v i) (lcg-uniform)))))

;;; --- the two kernels ---------------------------------------------------------
;;; RMSNorm without its gain vector: divide by the root mean square. `(vec:dot v v)`
;;; is the sum of the squares -- one accelerated reduction over the whole vector.
(defun rms-normalize (v)
  (vec:scale v (/ 1.0 (sqrt (+ (/ (vec:dot v v) (length v)) *eps*)))))

;;; One step: a GEMV, then the normalization. In a transformer this pair is a
;;; projection followed by a layer norm; here it is the whole program.
(defun step-once (w x) (rms-normalize (vec:matvec w x)))

;;; --- the fingerprint ---------------------------------------------------------
;;; Which component is the largest. Repeating the same matrix drives the vector
;;; toward that matrix's dominant direction, so the index moves for a few steps
;;; and then stops -- and where it stops is a fingerprint of every multiply-add
;;; along the way.
(defun argmax (v)
  (let ((best 0))
    (dotimes (i (length v) best)
      (when (> (aref v i) (aref v best)) (setq best i)))))

(defun iterate (w x n)
  (let ((indices '()))
    (dotimes (s n (reverse indices))
      (setq x (step-once w x))
      (setq indices (cons (argmax x) indices)))))

;;; --- run ---------------------------------------------------------------------
(defparameter *w* (random-matrix *dim* *dim*))
(defparameter *x* (random-vector *dim*))

(format t
 "simd-gemv: ~a steps of (vec:matvec w x) on a ~ax~a single-float matrix~%"
 *steps* *dim* *dim*)
(format t "~a multiply-adds, every one of them inside vec:matvec or vec:dot~%"
        (* *steps* (+ (* *dim* *dim*) *dim*)))

(let* ((start (get-internal-real-time))
       (indices (iterate *w* *x* *steps*))
       (elapsed (- (get-internal-real-time) start)))
  (format t "argmax after steps 1-10: ~a~%" (subseq indices 0 10))
  (format t "argmax after step ~a:   ~a  (the dominant direction)~%" *steps*
          (nth (- *steps* 1) indices))
  (format t "elapsed: ~a ms~%" elapsed)
  (format t
   "(re-run with --simd; the indices must not change, the time should)~%"))
