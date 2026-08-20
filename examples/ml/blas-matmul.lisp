;;;; The one kernel a library beats us at: the MATRIX product.
;;;;
;;;;   (linalg:matmul a b)   C = A B, a matrix times a matrix
;;;;
;;;; `--simd` gives this a hand-written lane loop. Every desktop and server
;;;; operating system can do far better, because a tuned BLAS -- a library whose
;;;; matrix multiply is blocked for the machine's caches and written against its
;;;; matrix instructions -- is either already in the OS or one package away.
;;;; `--blas` finds one and routes the product to it.
;;;;
;;;; A tuned BLAS is RECOMMENDED, never required: nothing is bundled, nothing is
;;;; downloaded, and a machine without one runs this program to the same output,
;;;; only slower. macOS needs no setup (Accelerate is part of the system); on
;;;; Linux, `sudo apt install libopenblas0-pthread` is the whole of it.
;;;;
;;;; RUN IT FOUR WAYS
;;;; ----------------
;;;;   rontolisp examples/ml/blas-matmul.lisp                 # portable definition
;;;;   rontolisp examples/ml/blas-matmul.lisp --simd          # our lane kernel
;;;;   rontolisp examples/ml/blas-matmul.lisp --blas          # the library
;;;;   rontolisp examples/ml/blas-matmul.lisp --simd --blas   # library, lanes below it
;;;;
;;;;   rontolisp examples/ml/blas-matmul.lisp -o Blas.class --blas
;;;;   java --enable-native-access=ALL-UNNAMED Blas
;;;;
;;;; The printed integers must not change. The elapsed time should. Measured on
;;;; an Apple M4 Max, ms for ONE 128x128 double-float product:
;;;;
;;;;                  portable      --simd      --blas
;;;;   interpreter     1848         0.62        0.034
;;;;   JVM               --         0.37        0.043
;;;;   wasm-GC           60         1.4         --
;;;;
;;;; So `--simd` is worth about 3000x here over the portable definition, and the
;;;; library another 9-18x on top of that. Both are the same interception seam:
;;;; with both flags the product tries the library first, the lane kernel next,
;;;; and the portable definition last.
;;;;
;;;; `--blas` reaches the interpreter (the native binary included) and the JVM
;;;; class output. A tuned BLAS is called through the foreign function API, which
;;;; WASM does not have, so `-o prog.wasm --blas` is an error rather than a silent
;;;; no-op; on the WASM backends `--simd` is the acceleration this program gets.
;;;;
;;;; Which library was bound, or why none was:
;;;;
;;;;   RONTOLISP_BLAS_VERBOSE=1 rontolisp examples/ml/blas-matmul.lisp --blas
;;;;
;;;; A tuned BLAS is also MULTI-THREADED, which nothing else in rontolisp is: one
;;;; `linalg:matmul` may occupy every core of the machine. Cap it with the
;;;; library's own variable (OPENBLAS_NUM_THREADS, VECLIB_MAXIMUM_THREADS) when
;;;; the program shares the machine.
;;;;
;;;; DETERMINISM
;;;; -----------
;;;; A library blocks and reorders its reduction, so an accelerated product is
;;;; close to the portable definition rather than equal to it -- over INEXACT
;;;; inputs. Every entry here is a small integer, so every product and every sum
;;;; is exact at double, and reordering cannot move a single bit. That is why
;;;; this program can print its numbers and expect them to match everywhere.
;;;; Only integers are printed: the WASM backends round a float to about seven
;;;; significant digits when printing, so a float would not compare across
;;;; backends.

;;; --- size --------------------------------------------------------------------
;;; 128 is small enough for the portable definition to finish on the interpreter
;;; and large enough for the library to be three orders of magnitude ahead. One
;;; product is all it takes to see that -- but an accelerated one finishes inside
;;; this clock's 1 ms tick, so raise *reps* when you want to time it.
(defparameter *dim* 128)

(defparameter *reps* 1)

;;; --- deterministic pseudo-random numbers -------------------------------------
;;; A Lehmer generator: every intermediate stays below 2^23, which fits the WASM
;;; backend's integer range, so the stream is identical on all backends.
(defvar *lcg-state* 7)

(defun lcg-next ()
  (setq *lcg-state* (mod (+ (* *lcg-state* 75) 74) 65537))
  *lcg-state*)

;;; --- the packed float arrays -------------------------------------------------
;;; No `:element-type` keyword: these are `double-float`, linalg's DEFAULT width
;;; -- the width `--blas` covers and the one almost every program here uses.
;;; Entries are integers in [0, 63], so a cell of the product is a sum of *dim*
;;; exact products and is itself exact.
(defun random-matrix (n)
  (let ((m (linalg:zeros (list n n))))
    (dotimes (i n m) (dotimes (j n) (setf (aref m i j) (mod (lcg-next) 64))))))

;;; --- run ---------------------------------------------------------------------
(defparameter *a* (random-matrix *dim*))
(defparameter *b* (random-matrix *dim*))

(defun product-times (a b n)
  (let ((c nil)) (dotimes (i n c) (setq c (linalg:matmul a b)))))

(format t "blas-matmul: ~ax~a double-float (linalg:matmul a b), ~a time(s)~%"
        *dim* *dim* *reps*)
(format t "~a multiply-adds per product, every one inside the matrix product~%"
        (* *dim* *dim* *dim*))

(let* ((start (get-internal-real-time))
       (c (product-times *a* *b* *reps*))
       (elapsed (- (get-internal-real-time) start)))
  (format t "corners: ~a~%"
          (list (round (aref c 0 0)) (round (aref c 0 (- *dim* 1)))
                (round (aref c (- *dim* 1) 0))
                (round (aref c (- *dim* 1) (- *dim* 1)))))
  (format t "trace:   ~a~%" (round (linalg:trace c)))
  (format t "elapsed: ~a ms~%" elapsed)
  (format t
   "(re-run with --simd, with --blas, with both -- the integers must not~%")
  (format t " change, the time should. Raise *reps* to time the fast ones.)~%"))
