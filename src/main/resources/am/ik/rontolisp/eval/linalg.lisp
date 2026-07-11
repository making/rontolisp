;; The linalg package: numpy-style vector/matrix operations over the built-in
;; arrays, written in rontolisp itself so a single implementation
;; runs on every backend: the interpreter loads these definitions lazily on
;; first use of a linalg: function, and the compile path splices them into the
;; program when it references the package (see LinalgLibrary.java).
;;
;; Portability constraints honored here (like json.lisp, see .kb/json.md):
;; - do loops always declare at least one variable; parameters are never
;;   assigned with setq (let-rebound instead).
;; - Arrays are packed float arrays, DOUBLE by default: every allocation flows
;;   through the one linalg::%la-make funnel (make-array :element-type, an unboxed
;;   (array double-float) / (array single-float)), and element reads coerce to
;;   double. linalg is width-polymorphic (todo-97): a constructor takes an
;;   optional element-type (default 'double-float; opt in with 'single-float for
;;   half the memory / 2x the SIMD lanes), and every transform PRESERVES its input
;;   width -- a #f (single-float) array stays #f through add/sub/mul/emap/transpose/
;;   dot/matmul/... (via linalg::%la-etype), so a #f value flowing in from vec: is
;;   never silently widened back to double (which would force a mixed-width --simd
;;   error on the next vec:matvec). Both %la-make branches take a LITERAL
;;   element-type so each backend picks the float[]/double[] (TYPE_F32ARR/F64ARR)
;;   repr statically -- interpreter, JVM AND wasm-GC all produce #f; only --no-gc is
;;   unsupported (no array type). linalg is speed-oriented, not exact -- integer/
;;   ratio inputs become doubles (numpy's model), and single-float trades precision
;;   for speed, so precision-critical det/inv/solve are best left double (the
;;   default; a singular integer matrix's determinant may be a tiny epsilon).
;;
;; Internal helpers use the linalg::%la- prefix. An array is walked with a
;; flat row-major index k via row-major-aref, so the elementwise operations
;; (add/sub/mul/div/emap/reductions/reshape/array-equal) and diff work for any
;; rank; dot/matmul/outer/det/inv/solve/trace/transpose stay defined for
;; rank <= 2, and gradient for vectors only.

;; --- internal helpers --------------------------------------------------------

(defun linalg::%la-make (dims init &optional element-type)
  ;; The single funnel through which EVERY linalg result-array allocation flows.
  ;; A LITERAL element-type of 'single-float builds a packed single-float array
  ;; (#f); anything else (the nil default) a packed double-float array (#d). Both
  ;; make-array calls take a literal :element-type, so every backend --
  ;; interpreter, JVM AND wasm-GC -- picks the double[]/float[] (TYPE_F64ARR/
  ;; F32ARR) representation statically; a runtime-computed element-type could not
  ;; (see .todo/95, .todo/97). init is coerced to the element width.
  (if (eq element-type 'single-float)
      (make-array dims :element-type 'single-float :initial-element init)
      (make-array dims :element-type 'double-float :initial-element init)))

(defun linalg::%la-etype (a)
  ;; The literal element-type symbol matching a's packed width -- 'single-float
  ;; for a #f array, else 'double-float -- so a transform that threads it into
  ;; %la-make PRESERVES the input width (a #f stays #f, a #d stays #d). This is
  ;; what makes the element-wise / product ops width-polymorphic. A general
  ;; (boxed) array reads back as element-type t, so it maps to 'double-float --
  ;; matching linalg's double default.
  (if (eq (array-element-type a) 'single-float) 'single-float 'double-float))

(defun linalg::%la-like (a)
  ;; A fresh zero-filled packed array with the same shape AND width as a.
  (linalg::%la-make (array-dimensions a) 0.0 (linalg::%la-etype a)))

(defun linalg::%la-bcast (%la-op %la-x %la-y)
  ;; Applies the binary function %la-op elementwise, broadcasting a scalar
  ;; operand over the other operand's shape. Array operands must have equal
  ;; shapes. The parameters keep their %la- names from when the compiled
  ;; backends resolved a captured name against a same-named user global
  ;; (fixed 2026-07-03); the prefix is harmless and stays.
  (cond ((and (numberp %la-x) (numberp %la-y)) (funcall %la-op %la-x %la-y))
        ((numberp %la-x)
         (linalg:emap (lambda (v) (funcall %la-op %la-x v)) %la-y))
        ((numberp %la-y)
         (linalg:emap (lambda (v) (funcall %la-op v %la-y)) %la-x))
        (t (progn
             (unless (equal (array-dimensions %la-x) (array-dimensions %la-y))
               (error "linalg: shape mismatch"))
             (let ((n (array-total-size %la-x))
                   (out (linalg::%la-like %la-x)))
               (do ((k 0 (+ k 1)))
                   ((>= k n) out)
                 (setf (row-major-aref out k)
                       (funcall %la-op (row-major-aref %la-x k)
                                (row-major-aref %la-y k)))))))))

(defun linalg::%la-reduce (f a init)
  ;; Folds f over every element of a (row-major), starting from init.
  (let ((n (array-total-size a))
        (acc init))
    (do ((k 0 (+ k 1)))
        ((>= k n) acc)
      (setq acc (funcall f acc (row-major-aref a k))))))

(defun linalg::%la-copy (a)
  ;; A fresh array with the same shape and elements as a.
  (linalg:emap (lambda (x) x) a))

(defun linalg::%la-pivot (m col n)
  ;; The row index in col..n-1 whose entry in the given column has the largest
  ;; absolute value (partial pivoting), or nil when the column is all zeros.
  (let ((best nil) (bestv 0))
    (do ((i col (+ i 1)))
        ((>= i n) best)
      (let ((x (abs (aref m i col))))
        (when (> x bestv)
          (setq best i)
          (setq bestv x))))))

(defun linalg::%la-swap-rows (m i j w)
  ;; Swaps rows i and j of the width-w matrix m in place.
  (do ((k 0 (+ k 1)))
      ((>= k w))
    (let ((tmp (aref m i k)))
      (setf (aref m i k) (aref m j k))
      (setf (aref m j k) tmp))))

(defun linalg::%la-eliminate (m col n w)
  ;; Zeroes the entries below the pivot m[col][col] in the width-w matrix m.
  (let ((piv (aref m col col)))
    (do ((i (+ col 1) (+ i 1)))
        ((>= i n))
      (let ((f (/ (aref m i col) piv)))
        (unless (= f 0)
          (do ((j col (+ j 1)))
              ((>= j w))
            (setf (aref m i j) (- (aref m i j) (* f (aref m col j))))))))))

(defun linalg::%la-square-size (a)
  ;; The order of the square matrix a, or an error.
  (let ((d (array-dimensions a)))
    (unless (and (cdr d) (= (car d) (car (cdr d))))
      (error "linalg: expects a square matrix"))
    (car d)))

(defun linalg::%la-dot-vv (u v)
  ;; The inner product of two equal-length vectors.
  (let ((n (length u)))
    (unless (= n (length v))
      (error "linalg: dot expects equal-length vectors"))
    (let ((acc 0))
      (do ((i 0 (+ i 1)))
          ((>= i n) acc)
        (setq acc (+ acc (* (aref u i) (aref v i))))))))

(defun linalg::%la-dot-mv (a v)
  ;; Matrix times column vector -> vector.
  (let* ((d (array-dimensions a))
         (n (car d))
         (m (car (cdr d))))
    (unless (= m (length v))
      (error "linalg: dot dimension mismatch"))
    (let ((out (linalg::%la-make n 0.0 (linalg::%la-etype a))))
      (do ((i 0 (+ i 1)))
          ((>= i n) out)
        (let ((acc 0))
          (do ((k 0 (+ k 1)))
              ((>= k m))
            (setq acc (+ acc (* (aref a i k) (aref v k)))))
          (setf (aref out i) acc))))))

(defun linalg::%la-dot-vm (v a)
  ;; Row vector times matrix -> vector.
  (let* ((d (array-dimensions a))
         (n (car d))
         (m (car (cdr d))))
    (unless (= n (length v))
      (error "linalg: dot dimension mismatch"))
    (let ((out (linalg::%la-make m 0.0 (linalg::%la-etype v))))
      (do ((j 0 (+ j 1)))
          ((>= j m) out)
        (let ((acc 0))
          (do ((k 0 (+ k 1)))
              ((>= k n))
            (setq acc (+ acc (* (aref v k) (aref a k j)))))
          (setf (aref out j) acc))))))

(defun linalg::%la-matmul (a b)
  ;; Matrix times matrix -> matrix.
  (let* ((da (array-dimensions a))
         (db (array-dimensions b))
         (n (car da))
         (m (car (cdr da)))
         (p (car (cdr db))))
    (unless (= m (car db))
      (error "linalg: matmul inner dimensions differ"))
    (let ((out (linalg::%la-make (list n p) 0.0 (linalg::%la-etype a))))
      (do ((i 0 (+ i 1)))
          ((>= i n) out)
        (do ((j 0 (+ j 1)))
            ((>= j p))
          (let ((acc 0))
            (do ((k 0 (+ k 1)))
                ((>= k m))
              (setq acc (+ acc (* (aref a i k) (aref b k j)))))
            (setf (aref out i j) acc)))))))

(defun linalg::%la-diff-1 (a)
  ;; One first-difference step along the last axis: out[..., i] =
  ;; a[..., i+1] - a[..., i]. The last axis is row-major-contiguous, so one
  ;; flat double loop (rows x within-row) handles every rank. An axis of
  ;; length 0 or 1 differences to length 0 (numpy's clamp, not an error).
  (let* ((d (array-dimensions a))
         (rd (reverse d))
         (c (car rd))
         (w (max 0 (- c 1)))
         (out (linalg::%la-make (reverse (cons w (cdr rd))) 0.0
                                (linalg::%la-etype a)))
         (rows (if (= c 0) 0 (/ (array-total-size a) c))))
    (do ((i 0 (+ i 1)))
        ((>= i rows) out)
      (do ((j 0 (+ j 1)))
          ((>= j w))
        (setf (row-major-aref out (+ (* i w) j))
              (- (row-major-aref a (+ (* i c) j 1))
                 (row-major-aref a (+ (* i c) j))))))))

;; --- constructors ------------------------------------------------------------

(defun linalg:zeros (shape &optional element-type)
  ;; A zero-filled vector (integer shape) or matrix (list shape). Double-float by
  ;; default; pass 'single-float to build a packed single-float (#f) result.
  (linalg::%la-make shape 0.0 element-type))

(defun linalg:ones (shape &optional element-type)
  ;; A one-filled vector or matrix (double by default; 'single-float for #f).
  (linalg::%la-make shape 1.0 element-type))

(defun linalg:full (shape value &optional element-type)
  ;; A vector or matrix with every element set to value (double by default;
  ;; 'single-float for #f).
  (linalg::%la-make shape value element-type))

(defun linalg:eye (n &optional element-type)
  ;; The n-by-n identity matrix (double by default; 'single-float for #f).
  (let ((m (linalg::%la-make (list n n) 0.0 element-type)))
    (do ((i 0 (+ i 1)))
        ((>= i n) m)
      (setf (aref m i i) 1))))

(defun linalg:arange (a &optional b step element-type)
  ;; (arange stop), (arange start stop), or (arange start stop step): the vector
  ;; of numbers from start (default 0) up to but excluding stop, advancing by step
  ;; (default 1; may be negative). Double-float by default; pass 'single-float for
  ;; a packed single-float (#f) result. step is always a number and an element-type
  ;; a (non-nil) symbol, so (arange 0 10 'single-float) reads the symbol as the
  ;; element-type (step defaulting to 1) and (arange 0 10 2 'single-float) keeps both.
  (let* ((et (if (and (symbolp step) step) step element-type))
         (stp (if (and (symbolp step) step) nil step))
         (start (if b a 0))
         (stop (if b b a))
         (d (if stp stp 1))
         (count (ceiling (/ (- stop start) d)))
         (n (max 0 count))
         (out (linalg::%la-make n 0.0 et)))
    (do ((i 0 (+ i 1))
         (x start (+ x d)))
        ((>= i n) out)
      (setf (aref out i) x))))

(defun linalg:linspace (start stop n &optional element-type)
  ;; The vector of n evenly spaced numbers from start to stop inclusive (double by
  ;; default; pass 'single-float for a packed single-float (#f) result).
  (let ((out (linalg::%la-make n 0.0 element-type)))
    (if (= n 1)
        (progn (setf (aref out 0) start) out)
        (let ((step (/ (- stop start) (- n 1))))
          (do ((i 0 (+ i 1)))
              ((>= i n) out)
            (setf (aref out i) (+ start (* step i))))))))

(defun linalg:from-list (lst &optional element-type)
  ;; A vector from a flat list, or a matrix from a list of equal-length rows
  ;; (double by default; pass 'single-float for a packed single-float (#f) result).
  (if (consp (car lst))
      (let* ((r (length lst))
             (c (length (car lst)))
             (m (linalg::%la-make (list r c) 0.0 element-type)))
        (do ((rows lst (cdr rows))
             (i 0 (+ i 1)))
            ((null rows) m)
          (do ((cells (car rows) (cdr cells))
               (j 0 (+ j 1)))
              ((null cells))
            (setf (aref m i j) (car cells)))))
      (let* ((n (length lst))
             (v (linalg::%la-make n 0.0 element-type)))
        (do ((cells lst (cdr cells))
             (i 0 (+ i 1)))
            ((null cells) v)
          (setf (aref v i) (car cells))))))

(defun linalg:to-list (a)
  ;; The inverse of from-list: a flat list for a vector, a list of row lists
  ;; for a matrix.
  (let ((d (array-dimensions a)))
    (if (cdr d)
        (let ((rows nil))
          (do ((i (- (car d) 1) (- i 1)))
              ((< i 0) rows)
            (let ((row nil))
              (do ((j (- (car (cdr d)) 1) (- j 1)))
                  ((< j 0))
                (setq row (cons (aref a i j) row)))
              (setq rows (cons row rows)))))
        (coerce a 'list))))

;; --- shape -------------------------------------------------------------------

(defun linalg:shape (a)
  ;; The dimension sizes as a list: (n) for a vector, (rows cols) for a matrix.
  (array-dimensions a))

(defun linalg:size (a)
  ;; The total element count.
  (array-total-size a))

(defun linalg:reshape (a shape)
  ;; A fresh array with the given shape and the same row-major elements (same
  ;; width as a: a #f reshapes to #f, a #d to #d).
  (let* ((out (linalg::%la-make shape 0.0 (linalg::%la-etype a)))
         (n (array-total-size a)))
    (unless (= n (array-total-size out))
      (error "linalg: reshape size mismatch"))
    (do ((k 0 (+ k 1)))
        ((>= k n) out)
      (setf (row-major-aref out k) (row-major-aref a k)))))

(defun linalg:flatten (a)
  ;; The elements of a as a fresh rank-1 vector (row-major).
  (linalg:reshape a (linalg:size a)))

(defun linalg:transpose (a)
  ;; The transpose of a matrix; a vector is returned unchanged (like numpy).
  (let ((d (array-dimensions a)))
    (if (cdr d)
        (let* ((r (car d))
               (c (car (cdr d)))
               (m (linalg::%la-make (list c r) 0.0 (linalg::%la-etype a))))
          (do ((i 0 (+ i 1)))
              ((>= i r) m)
            (do ((j 0 (+ j 1)))
                ((>= j c))
              (setf (aref m j i) (aref a i j)))))
        a)))

;; --- elementwise arithmetic (scalar broadcasting) ----------------------------

(defun linalg:add (a b)
  ;; Elementwise a + b; either operand may be a scalar.
  (linalg::%la-bcast #'+ a b))

(defun linalg:sub (a b)
  ;; Elementwise a - b; either operand may be a scalar.
  (linalg::%la-bcast #'- a b))

(defun linalg:mul (a b)
  ;; Elementwise (Hadamard) a * b; either operand may be a scalar.
  (linalg::%la-bcast #'* a b))

(defun linalg:div (a b)
  ;; Elementwise a / b; either operand may be a scalar.
  (linalg::%la-bcast #'/ a b))

(defun linalg:emap (f a)
  ;; A fresh array with f applied to every element of a.
  (let ((n (array-total-size a))
        (out (linalg::%la-like a)))
    (do ((k 0 (+ k 1)))
        ((>= k n) out)
      (setf (row-major-aref out k) (funcall f (row-major-aref a k))))))

;; --- named elementwise ufuncs (numpy parity, todo 109) ------------------------
;; Each is a named emap so the common per-element operations need no boxed
;; funcall under --simd (the names are interceptable; emap itself never is).
;; square and reciprocal ride the mul/div kernels instead of needing their own.

(defun linalg:exp (a)
  ;; Elementwise e^x (numpy np.exp).
  (linalg:emap (function exp) a))

(defun linalg:log (a)
  ;; Elementwise natural log (numpy np.log).
  (linalg:emap (function log) a))

(defun linalg:tanh (a)
  ;; Elementwise hyperbolic tangent (numpy np.tanh).
  (linalg:emap (function tanh) a))

(defun linalg:sin (a)
  ;; Elementwise sine (numpy np.sin).
  (linalg:emap (function sin) a))

(defun linalg:cos (a)
  ;; Elementwise cosine (numpy np.cos).
  (linalg:emap (function cos) a))

(defun linalg:tan (a)
  ;; Elementwise tangent (numpy np.tan).
  (linalg:emap (function tan) a))

(defun linalg:asin (a)
  ;; Elementwise arc sine (numpy np.arcsin).
  (linalg:emap (function asin) a))

(defun linalg:acos (a)
  ;; Elementwise arc cosine (numpy np.arccos).
  (linalg:emap (function acos) a))

(defun linalg:atan (a)
  ;; Elementwise arc tangent (numpy np.arctan).
  (linalg:emap (function atan) a))

(defun linalg:sinh (a)
  ;; Elementwise hyperbolic sine (numpy np.sinh).
  (linalg:emap (function sinh) a))

(defun linalg:cosh (a)
  ;; Elementwise hyperbolic cosine (numpy np.cosh).
  (linalg:emap (function cosh) a))

(defun linalg:sqrt (a)
  ;; Elementwise square root (numpy np.sqrt).
  (linalg:emap (function sqrt) a))

(defun linalg:abs (a)
  ;; Elementwise absolute value (numpy np.abs).
  (linalg:emap (function abs) a))

(defun linalg:square (a)
  ;; Elementwise x * x (numpy np.square).
  (linalg:mul a a))

(defun linalg:negative (a)
  ;; Elementwise negation (numpy np.negative); (- x) so -0.0 edges match minus.
  (linalg:emap (lambda (x) (- x)) a))

(defun linalg:sign (a)
  ;; Elementwise signum (numpy np.sign).
  (linalg:emap (function signum) a))

(defun linalg:reciprocal (a)
  ;; Elementwise 1 / x (numpy np.reciprocal, float semantics).
  (linalg:div 1 a))

;; --- comparison-select ufuncs (numpy parity, todo 109 Phase 3) ----------------
;; Defined by the strict comparison select, NOT a min/max primitive: the second
;; operand wins whenever the comparison is false -- ties (a -0.0 / 0.0 pair takes
;; the second) and unordered NaN comparisons included. Same rule as linalg:amax;
;; > and < agree bit-for-bit across backends since todo-108.

(defun linalg:maximum (a b)
  ;; Elementwise larger of a and b (numpy np.maximum); either may be a scalar.
  (linalg::%la-bcast (lambda (x y) (if (> x y) x y)) a b))

(defun linalg:minimum (a b)
  ;; Elementwise smaller of a and b (numpy np.minimum); either may be a scalar.
  (linalg::%la-bcast (lambda (x y) (if (< x y) x y)) a b))

(defun linalg:clip (a lo hi)
  ;; Elementwise min(max(x, lo), hi) with scalar bounds (numpy np.clip). The
  ;; composition rides the maximum/minimum kernels under --simd (the
  ;; square/reciprocal pattern); a NaN element becomes lo (the first select's
  ;; comparison is false), and inverted bounds (lo > hi) end at hi.
  (linalg:minimum (linalg:maximum a lo) hi))

(defun linalg:relu (a)
  ;; Elementwise max(x, 0.0): (linalg:maximum a 0.0), so a NaN or -0.0 element
  ;; becomes 0.0. Rides the maximum kernel under --simd.
  (linalg:maximum a 0.0))

;; --- products ----------------------------------------------------------------

(defun linalg:dot (a b)
  ;; numpy-style dot: vector . vector -> scalar, matrix . vector -> vector,
  ;; vector . matrix -> vector, matrix . matrix -> matrix; a scalar operand
  ;; multiplies elementwise.
  (cond ((or (numberp a) (numberp b)) (linalg:mul a b))
        (t (let ((ra (cdr (array-dimensions a)))
                 (rb (cdr (array-dimensions b))))
             (cond ((and (null ra) (null rb)) (linalg::%la-dot-vv a b))
                   ((and ra (null rb)) (linalg::%la-dot-mv a b))
                   ((and (null ra) rb) (linalg::%la-dot-vm a b))
                   (t (linalg::%la-matmul a b)))))))

(defun linalg:matmul (a b)
  ;; Matrix product (also matrix . vector); rejects scalar operands.
  (when (or (numberp a) (numberp b))
    (error "linalg: matmul expects arrays"))
  (linalg:dot a b))

(defun linalg:outer (u v)
  ;; The outer product of two vectors (inputs are flattened first, like numpy).
  (let* ((uf (linalg:flatten u))
         (vf (linalg:flatten v))
         (n (length uf))
         (m (length vf))
         (out (linalg::%la-make (list n m) 0.0 (linalg::%la-etype uf))))
    (do ((i 0 (+ i 1)))
        ((>= i n) out)
      (do ((j 0 (+ j 1)))
          ((>= j m))
        (setf (aref out i j) (* (aref uf i) (aref vf j)))))))

;; --- reductions --------------------------------------------------------------

(defun linalg:sum (a)
  ;; The sum of every element.
  (linalg::%la-reduce #'+ a 0))

(defun linalg:mean (a)
  ;; The arithmetic mean of every element.
  (/ (linalg:sum a) (linalg:size a)))

(defun linalg:amax (a)
  ;; The largest element.
  (let ((n (array-total-size a)))
    (when (= n 0)
      (error "linalg: amax of an empty array"))
    (let ((best (row-major-aref a 0)))
      (do ((k 1 (+ k 1)))
          ((>= k n) best)
        (let ((x (row-major-aref a k)))
          (when (> x best)
            (setq best x)))))))

(defun linalg:amin (a)
  ;; The smallest element.
  (let ((n (array-total-size a)))
    (when (= n 0)
      (error "linalg: amin of an empty array"))
    (let ((best (row-major-aref a 0)))
      (do ((k 1 (+ k 1)))
          ((>= k n) best)
        (let ((x (row-major-aref a k)))
          (when (< x best)
            (setq best x)))))))

(defun linalg:argmax (v)
  ;; The index of the largest element of a vector (first on ties).
  (let ((n (length v)))
    (when (= n 0)
      (error "linalg: argmax of an empty vector"))
    (let ((best (aref v 0)) (bi 0))
      (do ((i 1 (+ i 1)))
          ((>= i n) bi)
        (when (> (aref v i) best)
          (setq best (aref v i))
          (setq bi i))))))

(defun linalg:argmin (v)
  ;; The index of the smallest element of a vector (first on ties).
  (let ((n (length v)))
    (when (= n 0)
      (error "linalg: argmin of an empty vector"))
    (let ((best (aref v 0)) (bi 0))
      (do ((i 1 (+ i 1)))
          ((>= i n) bi)
        (when (< (aref v i) best)
          (setq best (aref v i))
          (setq bi i))))))

(defun linalg:norm (a)
  ;; The Euclidean (L2 / Frobenius) norm.
  (sqrt (linalg:sum (linalg:emap (lambda (x) (* x x)) a))))

(defun linalg:trace (a)
  ;; The sum of the main-diagonal elements of a square matrix.
  (let ((n (linalg::%la-square-size a))
        (acc 0))
    (do ((i 0 (+ i 1)))
        ((>= i n) acc)
      (setq acc (+ acc (aref a i i))))))

;; --- calculus (numpy diff / gradient) -----------------------------------------

(defun linalg:diff (a &optional n)
  ;; The n-th discrete difference along the last axis (numpy np.diff):
  ;; out[..., i] = a[..., i+1] - a[..., i], applied n times (default 1); each
  ;; step shortens the last axis by one (clamped at 0, like numpy). Works for
  ;; any rank; the result is a fresh packed array of a's width (so n = 0
  ;; returns a packed COPY, where numpy returns the input itself).
  (let ((times (if n n 1)))
    (when (< times 0)
      (error "linalg: diff order must be non-negative"))
    (if (= times 0)
        (linalg::%la-copy a)
        (let ((out (linalg::%la-diff-1 a)))
          (do ((k 1 (+ k 1)))
              ((>= k times) out)
            (setq out (linalg::%la-diff-1 out)))))))

(defun linalg:gradient (f &optional x)
  ;; The numerical derivative of a vector of samples (numpy np.gradient):
  ;; second-order central differences at interior points, first-order
  ;; one-sided differences at the two ends (numpy's default edge_order 1),
  ;; so the result has the SAME length as f (unlike diff). x is an optional
  ;; uniform sample spacing (a number, default 1) or a coordinate vector of
  ;; the same length as f (non-uniform spacing, numpy's exact interior
  ;; formula). Vectors only -- numpy's rank-2 gradient returns one array per
  ;; axis, which has no lite representation here. Needs at least 2 samples.
  (let ((d (array-dimensions f)))
    (when (cdr d)
      (error "linalg: gradient expects a vector"))
    (let ((n (car d)))
      (when (< n 2)
        (error "linalg: gradient needs at least 2 samples"))
      (let ((out (linalg::%la-like f)))
        (if (or (null x) (numberp x))
            (let ((h (if x x 1)))
              ;; Uniform spacing: (f[i+1] - f[i-1]) / 2h, numpy's fast path
              ;; (bit-identical to it, not the general formula below with
              ;; hs = hd, whose extra multiplies could round differently).
              (setf (aref out 0) (/ (- (aref f 1) (aref f 0)) h))
              (setf (aref out (- n 1))
                    (/ (- (aref f (- n 1)) (aref f (- n 2))) h))
              (do ((i 1 (+ i 1)))
                  ((>= i (- n 1)) out)
                (setf (aref out i)
                      (/ (- (aref f (+ i 1)) (aref f (- i 1))) (* 2 h)))))
            (progn
              (unless (= (length x) n)
                (error "linalg: gradient coordinates must match the sample length"))
              (setf (aref out 0)
                    (/ (- (aref f 1) (aref f 0)) (- (aref x 1) (aref x 0))))
              (setf (aref out (- n 1))
                    (/ (- (aref f (- n 1)) (aref f (- n 2)))
                       (- (aref x (- n 1)) (aref x (- n 2)))))
              (do ((i 1 (+ i 1)))
                  ((>= i (- n 1)) out)
                ;; numpy's second-order interior formula for non-uniform
                ;; spacing, exact for quadratics: with hs = x[i] - x[i-1]
                ;; and hd = x[i+1] - x[i],
                ;; (hs^2 f[i+1] + (hd^2 - hs^2) f[i] - hd^2 f[i-1])
                ;;   / (hs hd (hs + hd)).
                (let ((hs (- (aref x i) (aref x (- i 1))))
                      (hd (- (aref x (+ i 1)) (aref x i))))
                  (setf (aref out i)
                        (/ (- (+ (* hs hs (aref f (+ i 1)))
                                 (* (- (* hd hd) (* hs hs)) (aref f i)))
                              (* hd hd (aref f (- i 1))))
                           (* hs hd (+ hs hd))))))))))))

;; --- linear algebra ----------------------------------------------------------

(defun linalg:det (a)
  ;; The determinant, by Gaussian elimination with partial pivoting (in
  ;; floating point).
  (let ((n (linalg::%la-square-size a)))
    (let ((m (linalg::%la-copy a))
          (det 1))
      (do ((col 0 (+ col 1)))
          ((or (>= col n) (= det 0)) det)
        (let ((p (linalg::%la-pivot m col n)))
          (if (null p)
              (setq det 0.0)
              (progn
                (unless (= p col)
                  (linalg::%la-swap-rows m p col n)
                  (setq det (- 0 det)))
                (setq det (* det (aref m col col)))
                (linalg::%la-eliminate m col n n))))))))

(defun linalg:inv (a)
  ;; The inverse of a square matrix, by Gauss-Jordan elimination on the
  ;; augmented matrix [a | I] (in floating point); signals an error for a
  ;; singular matrix.
  (let* ((n (linalg::%la-square-size a))
         (w (* 2 n))
         (et (linalg::%la-etype a))
         (m (linalg::%la-make (list n w) 0.0 et)))
    (do ((i 0 (+ i 1)))
        ((>= i n))
      (do ((j 0 (+ j 1)))
          ((>= j n))
        (setf (aref m i j) (aref a i j)))
      (setf (aref m i (+ n i)) 1))
    (do ((col 0 (+ col 1)))
        ((>= col n))
      (let ((p (linalg::%la-pivot m col n)))
        (when (null p)
          (error "linalg: inv of a singular matrix"))
        (unless (= p col)
          (linalg::%la-swap-rows m p col w))
        (linalg::%la-eliminate m col n w)))
    (do ((col (- n 1) (- col 1)))
        ((< col 0))
      (let ((piv (aref m col col)))
        (do ((j col (+ j 1)))
            ((>= j w))
          (setf (aref m col j) (/ (aref m col j) piv)))
        (do ((i 0 (+ i 1)))
            ((>= i col))
          (let ((f (aref m i col)))
            (unless (= f 0)
              (do ((j col (+ j 1)))
                  ((>= j w))
                (setf (aref m i j) (- (aref m i j) (* f (aref m col j))))))))))
    (let ((out (linalg::%la-make (list n n) 0.0 et)))
      (do ((i 0 (+ i 1)))
          ((>= i n) out)
        (do ((j 0 (+ j 1)))
            ((>= j n))
          (setf (aref out i j) (aref m i (+ n j))))))))

(defun linalg:solve (a b)
  ;; Solves a . x = b for x, where b is a vector or a matrix (in floating
  ;; point).
  (linalg:dot (linalg:inv a) b))

;; --- comparison --------------------------------------------------------------

(defun linalg:array-equal (a b)
  ;; t when a and b have the same shape and numerically equal elements
  ;; (like numpy array_equal: 1 and 1.0 compare equal).
  (if (equal (array-dimensions a) (array-dimensions b))
      (let ((n (array-total-size a))
            (ok t))
        (do ((k 0 (+ k 1)))
            ((or (>= k n) (null ok)) ok)
          (let ((x (row-major-aref a k))
                (y (row-major-aref b k)))
            (unless (if (and (numberp x) (numberp y)) (= x y) (equal x y))
              (setq ok nil)))))
      nil))
