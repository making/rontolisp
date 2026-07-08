;; The simd package: portable packed-f64 vector kernels (see .kb/simd.md).
;;
;; A simd vector is a rank-1 packed (array double-float) -- the dedicated
;; unboxed float-array type, produced by (make-array n :element-type 'double-float).
;; The built-in aref / aset / length / make-array interoperate with it on every
;; backend, so simd:aref / simd:aset / simd:length are thin wrappers over the
;; generic ops. THIS scalar definition is the implementation and the cross-backend
;; correctness oracle on the interpreter, the JVM compiler and the wasm-GC compiler;
;; the JVM --simd flag intercepts the vectorizable kernels (add/sub/mul/scale/dot/sum)
;; and lowers them to jdk.incubator.vector lane loops, and the --no-gc scalar WASM
;; backend lowers the whole simd: surface to real fixed-width WASM SIMD (v128 /
;; f64x2.*) over a packed [len][f64...] linear-memory block.
;;
;; Portability constraints honored here (like linalg.lisp / json.lisp): do loops
;; always declare at least one variable, parameters are never assigned with setq (a
;; let-bound copy is mutated instead), and elements are coerced to double with
;; (float x) to match the packed double-float element model.

;; --- construction ------------------------------------------------------------

(defun simd:zeros (n)
  (make-array n :element-type 'double-float :initial-element 0.0))

(defun simd:ones (n)
  (make-array n :element-type 'double-float :initial-element 1.0))

(defun simd:arange (n)
  (let ((v (make-array n :element-type 'double-float :initial-element 0.0)))
    (dotimes (i n v)
      (setf (aref v i) (float i)))))

(defun simd:from-list (xs)
  (let ((v (make-array (length xs) :element-type 'double-float :initial-element 0.0))
        (i 0))
    (dolist (x xs v)
      (setf (aref v i) (float x))
      (setq i (+ i 1)))))

(defun simd:to-list (v)
  (let ((acc nil)
        (n (length v)))
    (dotimes (i n acc)
      (setq acc (cons (aref v (- n 1 i)) acc)))))

;; --- element access (thin wrappers so simd:aref / simd:length resolve) --------

(defun simd:aref (v i)
  (aref v i))

(defun simd:aset (v i x)
  (setf (aref v i) (float x)))

(defun simd:length (v)
  (length v))

;; --- element-wise kernels (return a fresh vector) ----------------------------

(defun simd::%map2 (%simd-op %simd-a %simd-b)
  ;; %simd-op applied element-wise over two equal-length vectors -> a fresh vector.
  ;; The %simd- parameter names avoid a compiled-backend clash with a same-named
  ;; user global (the linalg.lisp %la- convention).
  (let ((out (make-array (length %simd-a) :element-type 'double-float :initial-element 0.0)))
    (dotimes (i (length %simd-a) out)
      (setf (aref out i) (funcall %simd-op (aref %simd-a i) (aref %simd-b i))))))

(defun simd:add (a b)
  (simd::%map2 #'+ a b))

(defun simd:sub (a b)
  (simd::%map2 #'- a b))

(defun simd:mul (a b)
  (simd::%map2 #'* a b))

(defun simd:scale (v s)
  (let ((out (make-array (length v) :element-type 'double-float :initial-element 0.0)))
    (dotimes (i (length v) out)
      (setf (aref out i) (* (aref v i) s)))))

;; --- reductions (return a scalar) --------------------------------------------

(defun simd:sum (v)
  (let ((acc 0.0))
    (dotimes (i (length v) acc)
      (setq acc (+ acc (aref v i))))))

(defun simd:dot (a b)
  (let ((acc 0.0))
    (dotimes (i (length a) acc)
      (setq acc (+ acc (* (aref a i) (aref b i)))))))

(defun simd:mean (v)
  (/ (simd:sum v) (length v)))

(defun simd:norm (v)
  (sqrt (simd:dot v v)))
