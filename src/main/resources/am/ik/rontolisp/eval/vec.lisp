;; The vec package: portable packed-float vector kernels (see .kb/vec.md).
;;
;; A vec vector is a rank-1 packed unboxed float array -- either double-float
;; (#d / make-array :element-type 'double-float) or single-float (#f /
;; 'single-float), on every backend. The kernels are width-polymorphic: the
;; element-wise ops (add/sub/mul/scale) preserve the input width via vec::%make-like,
;; while the reductions (sum/dot) always fold to an f64 scalar (a single-float lane
;; is widened on read). The built-in aref / aset / length / make-array interoperate
;; with either width on every backend, so vec:aref / vec:aset / vec:length are
;; thin wrappers over the generic ops. THIS scalar definition is the implementation and the cross-backend
;; correctness oracle on the interpreter, the JVM compiler and the wasm-GC compiler;
;; the JVM --simd flag intercepts the vectorizable kernels (add/sub/mul/scale/dot/sum)
;; and lowers them to jdk.incubator.vector lane loops, and the --no-gc scalar WASM
;; backend lowers the whole vec: surface to real fixed-width WASM SIMD (v128 /
;; f64x2.*) over a packed [len][f64...] linear-memory block.
;;
;; Portability constraints honored here (like linalg.lisp / json.lisp): do loops
;; always declare at least one variable, parameters are never assigned with setq (a
;; let-bound copy is mutated instead), and elements are coerced to double with
;; (float x) to match the packed double-float element model.

;; --- construction ------------------------------------------------------------

;; A fresh length-n packed vector filled with init, double by default; a LITERAL
;; 'single-float element-type builds #f. Both make-array calls take a literal
;; :element-type so every backend picks the double[]/float[] (TYPE_F64ARR/F32ARR)
;; repr statically -- the count-based constructor counterpart of vec::%make-like
;; (which follows a prototype's width) and the mirror of linalg::%la-make. On the
;; --no-gc scalar backend these constructors are intercepted natively by
;; NoGcWasmCompiler (which reads the same literal element-type -> F32VEC/F64VEC),
;; so this defun is only the interpreter / JVM / wasm-GC implementation.
(defun vec::%make (n init &optional element-type)
  (if (eq element-type 'single-float)
      (make-array n :element-type 'single-float :initial-element init)
      (make-array n :element-type 'double-float :initial-element init)))

(defun vec:zeros (n &optional element-type)
  (vec::%make n 0.0 element-type))

(defun vec:ones (n &optional element-type)
  (vec::%make n 1.0 element-type))

(defun vec:arange (n &optional element-type)
  (let ((v (vec::%make n 0.0 element-type)))
    (dotimes (i n v)
      (setf (aref v i) (float i)))))

(defun vec:from-list (xs)
  (let ((v (make-array (length xs) :element-type 'double-float :initial-element 0.0))
        (i 0))
    (dolist (x xs v)
      (setf (aref v i) (float x))
      (setq i (+ i 1)))))

(defun vec:to-list (v)
  (let ((acc nil)
        (n (length v)))
    (dotimes (i n acc)
      (setq acc (cons (aref v (- n 1 i)) acc)))))

;; --- element access (thin wrappers so vec:aref / vec:length resolve) --------

(defun vec:aref (v i)
  (aref v i))

(defun vec:aset (v i x)
  (setf (aref v i) (float x)))

(defun vec:length (v)
  (length v))

;; --- element-wise kernels (return a fresh vector) ----------------------------

;; A fresh zero-filled rank-1 packed vector of length %vec-n whose element type
;; matches the prototype vector, so the element-wise kernels PRESERVE single/double
;; width (a #f input yields a #f result, a #d input a #d result -- matching the
;; --simd bridge). Both make-array calls take a literal :element-type, so every
;; backend -- interpreter, JVM AND wasm-GC -- picks the double[]/float[]
;; (TYPE_F64ARR/TYPE_F32ARR) repr statically and produces #f directly; a
;; runtime-computed element-type could not. No #+/#-rontolisp-wasm reader
;; conditional is needed (an earlier split forced double on wasm-GC, from before
;; wasm-GC had TYPE_F32ARR); the --no-gc backend lowers vec: to native SIMD itself
;; and never splices this defun, so it is unaffected. Mirrors linalg::%la-make.
(defun vec::%make-like (%vec-proto %vec-n)
  (if (eq (array-element-type %vec-proto) 'single-float)
      (make-array %vec-n :element-type 'single-float :initial-element 0.0)
      (make-array %vec-n :element-type 'double-float :initial-element 0.0)))

(defun vec::%map2 (%vec-op %vec-a %vec-b)
  ;; %vec-op applied element-wise over two equal-length vectors -> a fresh vector.
  ;; The %vec- parameter names avoid a compiled-backend clash with a same-named
  ;; user global (the linalg.lisp %la- convention).
  (let ((out (vec::%make-like %vec-a (length %vec-a))))
    (dotimes (i (length %vec-a) out)
      (setf (aref out i) (funcall %vec-op (aref %vec-a i) (aref %vec-b i))))))

(defun vec:add (a b)
  (vec::%map2 #'+ a b))

(defun vec:sub (a b)
  (vec::%map2 #'- a b))

(defun vec:mul (a b)
  (vec::%map2 #'* a b))

(defun vec:scale (v s)
  (let ((out (vec::%make-like v (length v))))
    (dotimes (i (length v) out)
      (setf (aref out i) (* (aref v i) s)))))

;; --- reductions (return a scalar) --------------------------------------------

(defun vec:sum (v)
  (let ((acc 0.0))
    (dotimes (i (length v) acc)
      (setq acc (+ acc (aref v i))))))

(defun vec:dot (a b)
  (let ((acc 0.0))
    (dotimes (i (length a) acc)
      (setq acc (+ acc (* (aref a i) (aref b i)))))))

(defun vec:mean (v)
  (/ (vec:sum v) (length v)))

(defun vec:norm (v)
  (sqrt (vec:dot v v)))

;; --- matrix x vector (GEMV) --------------------------------------------------

;; y = W x, where W is a rank-2 packed matrix (d rows, n columns) and x is a
;; rank-1 packed vector of length n; the result is a fresh rank-1 packed vector
;; of length d with y[i] = dot(row i of W, x). The result width follows x (via
;; vec::%make-like), so a #d matrix/vector yields #d and, on the interpreter and
;; JVM, a #f pair yields #f. This is the scalar reference and the byte-identical
;; oracle for the JVM --simd bridge (which runs the same dot per row over
;; jdk.incubator.vector). The reads widen f32 -> f64 and the store narrows back,
;; exactly as the accelerated kernel does.
(defun vec:matvec (w x)
  (let* ((dims (array-dimensions w))
         (d (car dims))
         (n (cadr dims))
         (out (vec::%make-like x d)))
    (dotimes (i d out)
      (let ((acc 0.0))
        (dotimes (j n)
          (setq acc (+ acc (* (aref w i j) (aref x j)))))
        (setf (aref out i) acc)))))
