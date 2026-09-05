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
;; (which follows a prototype's width) and the mirror of linalg::%la-make. The
;; public constructors take it as a numpy-style :element-type keyword. On the
;; --no-gc scalar backend these constructors are intercepted natively by
;; NoGcWasmCompiler (which reads the same literal :element-type -> F32VEC/F64VEC),
;; so this defun is only the interpreter / JVM / wasm-GC implementation.
;; A cond rather than an if because there are THREE widths: every branch still passes
;; a LITERAL :element-type, which is what lets each backend pick the representation
;; statically. A width added to the umbrella has to be added here by hand -- an eq on a
;; symbol has no exhaustiveness to lean on, the same hole make-array's own dispatch has.
(defun vec::%make (n init &optional element-type)
  (cond ((eq element-type 'single-float)
         (make-array n :element-type 'single-float :initial-element init))
        ((eq element-type 'bfloat16)
         (make-array n :element-type 'bfloat16 :initial-element init))
        (t (make-array n :element-type 'double-float :initial-element init))))

(defun vec:zeros (n &key element-type) (vec::%make n 0.0 element-type))

(defun vec:ones (n &key element-type) (vec::%make n 1.0 element-type))

(defun vec:arange (n &key element-type)
  (let ((v (vec::%make n 0.0 element-type)))
    (dotimes (i n v) (setf (aref v i) (float i)))))

(defun vec:from-list (xs)
  (let ((v
         (make-array (length xs)
                     :element-type 'double-float
                     :initial-element 0.0))
        (i 0))
    (dolist (x xs v)
      (setf (aref v i) (float x))
      (setq i (+ i 1)))))

(defun vec:to-list (v)
  (let ((acc nil) (n (length v)))
    (dotimes (i n acc) (setq acc (cons (aref v (- n 1 i)) acc)))))

;; --- element access (thin wrappers so vec:aref / vec:length resolve) --------

(defun vec:aref (v i) (aref v i))

(defun vec:aset (v i x) (setf (aref v i) (float x)))

(defun vec:length (v) (length v))

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
  (cond
   ((eq (array-element-type %vec-proto) 'single-float)
    (make-array %vec-n :element-type 'single-float :initial-element 0.0))
   ((eq (array-element-type %vec-proto) 'bfloat16)
    (make-array %vec-n :element-type 'bfloat16 :initial-element 0.0))
   (t (make-array %vec-n :element-type 'double-float :initial-element 0.0))))

(defun vec::%map2 (%vec-op %vec-a %vec-b)
  ;; %vec-op applied element-wise over two equal-length vectors -> a fresh vector.
  ;; The %vec- parameter names avoid a compiled-backend clash with a same-named
  ;; user global (the linalg.lisp %la- convention).
  (let ((out (vec::%make-like %vec-a (length %vec-a))))
    (dotimes (i (length %vec-a) out)
      (setf (aref out i) (funcall %vec-op (aref %vec-a i) (aref %vec-b i))))))

(defun vec:add (a b) (vec::%map2 #'+ a b))

(defun vec:sub (a b) (vec::%map2 #'- a b))

(defun vec:mul (a b) (vec::%map2 #'* a b))

(defun vec:div (a b) (vec::%map2 #'/ a b))

(defun vec:scale (v s)
  (let ((out (vec::%make-like v (length v))))
    (dotimes (i (length v) out) (setf (aref out i) (* (aref v i) s)))))

;; The CL operator spellings of the four element-wise kernels above. Unlike their
;; linalg: siblings they are STRICTLY BINARY: every vec: kernel is fixed-arity and
;; allocation-explicit (the reason the -into family exists), so an n-ary spelling
;; that silently allocated one intermediate vector per extra operand would
;; contradict the package's contract -- and --no-gc, which intercepts every vec:
;; name natively instead of splicing these defuns, has no cons list to fold over.

(defun vec:+ (a b) (vec:add a b))

(defun vec:- (a b) (vec:sub a b))

(defun vec:* (a b) (vec:mul a b))

(defun vec:/ (a b) (vec:div a b))

;; --- element-wise unary ufuncs (numpy parity) ---------------------------------

;; %vec-op applied element-wise over one vector -> a fresh vector of the same
;; width. The element read widens f32 -> f64, the operator runs in f64, and the
;; store narrows back -- the same emap rule the --simd kernels reproduce.
(defun vec::%map1 (%vec-op %vec-v)
  (let ((out (vec::%make-like %vec-v (length %vec-v))))
    (dotimes (i (length %vec-v) out)
      (setf (aref out i) (funcall %vec-op (aref %vec-v i))))))

(defun vec:exp (v) (vec::%map1 #'exp v))

(defun vec:log (v) (vec::%map1 #'log v))

(defun vec:tanh (v) (vec::%map1 #'tanh v))

(defun vec:sin (v) (vec::%map1 #'sin v))

(defun vec:cos (v) (vec::%map1 #'cos v))

(defun vec:tan (v) (vec::%map1 #'tan v))

(defun vec:asin (v) (vec::%map1 #'asin v))

(defun vec:acos (v) (vec::%map1 #'acos v))

(defun vec:atan (v) (vec::%map1 #'atan v))

(defun vec:sinh (v) (vec::%map1 #'sinh v))

(defun vec:cosh (v) (vec::%map1 #'cosh v))

(defun vec:sqrt (v) (vec::%map1 #'sqrt v))

(defun vec:abs (v) (vec::%map1 #'abs v))

(defun vec:square (v)
  ;; x * x is vec:mul with itself, so it rides the mul kernels under --simd.
  (vec:mul v v))

(defun vec:negative (v)
  ;; (- x) is true negation, so (vec:negative #d(0.0)) is #d(-0.0).
  (vec::%map1 (lambda (x) (- x)) v))

(defun vec:sign (v) (vec::%map1 #'signum v))

(defun vec:reciprocal (v) (vec::%map1 (lambda (x) (/ 1.0 x)) v))

;; --- comparison-select ufuncs (numpy parity) ----------------------------------
;;
;; All four are defined by the strict comparison select (if (> x y) x y) and its
;; mirrors -- NOT by a min/max primitive. The second operand (or the bound) wins
;; whenever the comparison is false, which covers ties ((vec:maximum #d(-0.0)
;; #d(0.0)) is #d(0.0), the second element) and unordered NaN comparisons
;; ((vec:maximum #d(nan) w) takes w's element; (vec:maximum v #d(nan)) keeps the
;; NaN). This is the same first-strictly-greater-wins rule linalg:amax uses, and
;; the scalar > / < agree bit-for-bit on every backend, so the
;; --simd kernels mirror the comparison (a lane gt mask + bitselect, never the
;; IEEE lane min/max whose NaN/-0.0 semantics differ).

(defun vec:maximum (a b) (vec::%map2 (lambda (x y) (if (> x y) x y)) a b))

(defun vec:minimum (a b) (vec::%map2 (lambda (x y) (if (< x y) x y)) a b))

(defun vec:relu (v) (vec::%map1 (lambda (x) (if (> x 0.0) x 0.0)) v))

;; min(max(x, lo), hi) as the same nested selects linalg:clip composes from
;; linalg:maximum / linalg:minimum, so the two clips agree on every input --
;; including a NaN element (the first select's comparison is false, so it
;; becomes lo) and inverted bounds (lo > hi ends at hi).
(defun vec:clip (v lo hi)
  (vec::%map1
   (lambda (x) (let ((%vec-t (if (> x lo) x lo))) (if (< %vec-t hi) %vec-t hi)))
   v))

;; --- destination-passing kernels (write into out, allocate nothing) ----------

;; The allocating kernels above return a fresh vector, so a loop over them creates
;; one vector per iteration. On the WASM backends that memory is bump-allocated and
;; never freed, so the -into siblings below exist to hoist the
;; allocation out of the loop:
;;
;;   (let ((acc (vec:zeros n)))
;;     (dotimes (i steps) (vec:add-into acc acc d)))
;;
;; Each writes into out and returns it. out MAY alias a and/or b in the element-wise
;; kernels: out[i] depends only on a[i] and b[i], so in-place accumulation is
;; well-defined. out is NOT bounds-checked against the inputs (like vec:add itself,
;; which happily reads past a shorter operand) -- it must be at least as long.
;; Destination first, mirroring CL's own (map-into result fn &rest sequences).

(defun vec::%map2-into (%vec-out %vec-op %vec-a %vec-b)
  (dotimes (i (length %vec-a) %vec-out)
    (setf (aref %vec-out i) (funcall %vec-op (aref %vec-a i) (aref %vec-b i)))))

(defun vec:add-into (out a b) (vec::%map2-into out #'+ a b))

(defun vec:sub-into (out a b) (vec::%map2-into out #'- a b))

(defun vec:mul-into (out a b) (vec::%map2-into out #'* a b))

(defun vec:div-into (out a b) (vec::%map2-into out #'/ a b))

(defun vec:scale-into (out v s)
  (dotimes (i (length v) out) (setf (aref out i) (* (aref v i) s))))

;; The unary -into siblings. out MAY alias v (element i depends only on element
;; i, the add-into rule -- NOT the matvec-into one), so (vec:exp-into v v) is the
;; intended in-place update. Same contract as the binary -into kernels: same
;; width required, out at least as long, length unchecked.
(defun vec::%map1-into (%vec-out %vec-op %vec-v)
  (dotimes (i (length %vec-v) %vec-out)
    (setf (aref %vec-out i) (funcall %vec-op (aref %vec-v i)))))

(defun vec:exp-into (out v) (vec::%map1-into out #'exp v))

(defun vec:log-into (out v) (vec::%map1-into out #'log v))

(defun vec:tanh-into (out v) (vec::%map1-into out #'tanh v))

(defun vec:sin-into (out v) (vec::%map1-into out #'sin v))

(defun vec:cos-into (out v) (vec::%map1-into out #'cos v))

(defun vec:tan-into (out v) (vec::%map1-into out #'tan v))

(defun vec:asin-into (out v) (vec::%map1-into out #'asin v))

(defun vec:acos-into (out v) (vec::%map1-into out #'acos v))

(defun vec:atan-into (out v) (vec::%map1-into out #'atan v))

(defun vec:sinh-into (out v) (vec::%map1-into out #'sinh v))

(defun vec:cosh-into (out v) (vec::%map1-into out #'cosh v))

(defun vec:sqrt-into (out v) (vec::%map1-into out #'sqrt v))

(defun vec:abs-into (out v) (vec::%map1-into out #'abs v))

(defun vec:square-into (out v) (vec:mul-into out v v))

(defun vec:negative-into (out v) (vec::%map1-into out (lambda (x) (- x)) v))

(defun vec:sign-into (out v) (vec::%map1-into out #'signum v))

(defun vec:reciprocal-into (out v)
  (vec::%map1-into out (lambda (x) (/ 1.0 x)) v))

(defun vec:maximum-into (out a b)
  (vec::%map2-into out (lambda (x y) (if (> x y) x y)) a b))

(defun vec:minimum-into (out a b)
  (vec::%map2-into out (lambda (x y) (if (< x y) x y)) a b))

(defun vec:relu-into (out v)
  (vec::%map1-into out (lambda (x) (if (> x 0.0) x 0.0)) v))

(defun vec:clip-into (out v lo hi)
  (vec::%map1-into out
   (lambda (x) (let ((%vec-t (if (> x lo) x lo))) (if (< %vec-t hi) %vec-t hi)))
   v))

;; --- reductions (return a scalar) --------------------------------------------

(defun vec:sum (v)
  (let ((acc 0.0)) (dotimes (i (length v) acc) (setq acc (+ acc (aref v i))))))

(defun vec:dot (a b)
  (let ((acc 0.0))
    (dotimes (i (length a) acc) (setq acc (+ acc (* (aref a i) (aref b i)))))))

(defun vec:mean (v) (/ (vec:sum v) (length v)))

(defun vec:norm (v) (sqrt (vec:dot v v)))

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
  (if (rontolisp:quantized-matrix-p w)
      (vec::%matvec-quantized (vec::%make-like x (car (array-dimensions w))) w
                              x)
      (let* ((dims (array-dimensions w))
             (d (car dims))
             (n (cadr dims))
             (out (vec::%make-like x d)))
        (dotimes (i d out)
          (let ((acc 0.0))
            (dotimes (j n) (setq acc (+ acc (* (aref w i j) (aref x j)))))
            (setf (aref out i) acc))))))

;; y = W x over a Q8_0 quantized matrix W (rontolisp:quantize, a GGUF's Q8_0
;; tensor): ggml's Q8_0 x Q8_1 shape, runq.c's shape. x is quantized to int8 per
;; block of 32 first -- sx = amax / 127 in double, q = (round (/ x sx)), CL's
;; round-half-even, 0 where the block is all zero -- then each row folds, block
;; by block, FOUR exact integer dots (lane i over the block's columns j with
;; j mod 4 = i, the four int lanes the --simd kernel holds) and, per lane, ONE
;; SINGLE-FLOAT multiply-add lane * p, p = sw * sx narrowed to f32, into four f32
;; accumulators, folded once per row as (acc0 + acc2) + (acc1 + acc3) in f32; the
;; store narrows or widens to out's width. Every f32 step is spelled here as a
;; double operation narrowed through vec::%f32 -- a single-float cell -- which is
;; the f32 operation itself (a double has 53 >= 2 * 24 + 2 bits, so a product or
;; sum of two f32 values rounded once is correctly rounded). Integer sums are
;; exact in any order, so this defun and the --simd lane kernels (VecSimdKernels /
;; JvmSimdVectorTemplate) agree BIT FOR BIT. The four accumulators and the f32
;; width are the pinned part -- change them together with the kernels or not at
;; all. The dead quantized arm compiles on every backend; only the interpreter
;; and the JVM can ever reach it (.kb/quantized-matrix.md).
(defvar vec::%f32-cell
  (make-array 1 :element-type 'single-float :initial-element 0.0))

(defun vec::%f32 (v)
  ;; v rounded to single-float, as a double: the narrowing store of a #f array.
  (setf (aref vec::%f32-cell 0) v)
  (aref vec::%f32-cell 0))

(defun vec::%matvec-quantized (out w x)
  (let* ((dims (array-dimensions w))
         (d (car dims))
         (n (cadr dims))
         (nb (floor n 32))
         (xq (make-array n))
         (xs (make-array nb :element-type 'double-float :initial-element 0.0)))
    (dotimes (b nb)
      (let ((amax 0.0) (base (* b 32)))
        (dotimes (k 32)
          (let ((v (abs (aref x (+ base k))))) (when (> v amax) (setq amax v))))
        (let ((sx (/ amax 127.0)))
          (setf (aref xs b) sx)
          (dotimes (k 32)
            (let ((j (+ base k)))
              (setf (aref xq j)
                    (if (= sx 0.0) 0 (round (/ (aref x j) sx)))))))))
    (dotimes (i d out)
      (let ((acc0 0.0) (acc1 0.0) (acc2 0.0) (acc3 0.0))
        (dotimes (b nb)
          (let ((s0 0) (s1 0) (s2 0) (s3 0) (base (* b 32)))
            (dotimes (k 8)
              (let ((j (+ base (* 4 k))))
                (setq s0
                 (+ s0 (* (rontolisp::%quantized-quant w i j) (aref xq j))))
                (setq s1
                      (+ s1
                         (* (rontolisp::%quantized-quant w i (+ j 1))
                            (aref xq (+ j 1)))))
                (setq s2
                      (+ s2
                         (* (rontolisp::%quantized-quant w i (+ j 2))
                            (aref xq (+ j 2)))))
                (setq s3
                      (+ s3
                         (* (rontolisp::%quantized-quant w i (+ j 3))
                            (aref xq (+ j 3)))))))
            (let ((p
                   (vec::%f32
                    (* (rontolisp::%quantized-scale w i b) (aref xs b)))))
              (setq acc0 (vec::%f32 (+ acc0 (vec::%f32 (* s0 p)))))
              (setq acc1 (vec::%f32 (+ acc1 (vec::%f32 (* s1 p)))))
              (setq acc2 (vec::%f32 (+ acc2 (vec::%f32 (* s2 p)))))
              (setq acc3 (vec::%f32 (+ acc3 (vec::%f32 (* s3 p))))))))
        (setf (aref out i)
              (vec::%f32
               (+ (vec::%f32 (+ acc0 acc2)) (vec::%f32 (+ acc1 acc3)))))))))

(defun vec:matvec-into (out w x)
  (when (eq out x)
    (error "vec:matvec-into: out must not be the same vector as x"))
  (when (eq out w)
    (error "vec:matvec-into: out must not be the same array as w"))
  (if (rontolisp:quantized-matrix-p w)
      (vec::%matvec-quantized out w x)
      (let* ((dims (array-dimensions w)) (d (car dims)) (n (cadr dims)))
        (dotimes (i d out)
          (let ((acc 0.0))
            (dotimes (j n) (setq acc (+ acc (* (aref w i j) (aref x j)))))
            (setf (aref out i) acc))))))
