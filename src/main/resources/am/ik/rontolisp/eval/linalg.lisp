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
;;   double. linalg is width-polymorphic: a constructor takes an :element-type
;;   keyword (default 'double-float; opt in with 'single-float for half the
;;   memory / 2x the SIMD lanes), and every transform PRESERVES its input
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
;;
;; Options are numpy-style KEYWORD arguments (&key), never trailing positionals:
;; :element-type on every constructor, :axis / :keepdims on the reductions,
;; :n / :axis on diff. A compiled call site with literal keywords is what the
;; --simd interceptors (Jvm/WasmLinalgSimdCompiler, LinalgSimd) pattern-match,
;; so a spliced body that forwards an option must spell the keyword literally.

;; --- internal helpers --------------------------------------------------------

(defun linalg::%la-make (dims init &optional element-type)
  ;; The single funnel through which EVERY linalg result-array allocation flows.
  ;; A LITERAL element-type of 'single-float builds a packed single-float array
  ;; (#f); anything else (the nil default) a packed double-float array (#d). Both
  ;; make-array calls take a literal :element-type, so every backend --
  ;; interpreter, JVM AND wasm-GC -- picks the double[]/float[] (TYPE_F64ARR/
  ;; F32ARR) representation statically; a runtime-computed element-type could
  ;; not. init is coerced to the element width.
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

(defun linalg::%la-bcast-shape (dx dy)
  ;; The numpy broadcast shape of two dims lists: trailing axes align, and a
  ;; pair agrees when the extents are equal or either is 1 (a missing leading
  ;; axis counts as 1); the output extent is the larger one. Any other
  ;; disagreement is the shape-mismatch error.
  (let ((out nil))
    (do ((px (reverse dx) (cdr px)) (py (reverse dy) (cdr py)))
        ((and (null px) (null py)) out)
      (let ((a (if px (car px) 1)) (b (if py (car py) 1)))
        (unless (or (= a b) (= a 1) (= b 1)) (error "linalg: shape mismatch"))
        (setq out (cons (max a b) out))))))

(defun linalg::%la-bcast-strides (d od)
  ;; Row-major strides of the dims-d operand aligned to the broadcast shape od,
  ;; INNERMOST-FIRST, with 0 on every stretched axis (extent 1 or missing) so
  ;; the odometer walk in %la-bcast-loop re-reads the same element across it.
  (let ((acc 1) (out nil))
    (do ((pd (reverse d) (cdr pd)) (po (reverse od) (cdr po)))
        ((null po) (reverse out))
      (let ((n (if pd (car pd) 1)))
        (setq out (cons (if (= n 1) 0 acc) out))
        (setq acc (* acc n))))))

(defun linalg::%la-zero-counters (n)
  ;; A fresh list of n zeros: the odometer counters, rplaca-mutated in place.
  (let ((out nil))
    (do ((i 0 (+ i 1)))
        ((>= i n) out)
      (setq out (cons 0 out)))))

(defun linalg::%la-strides (dims)
  ;; The row-major strides of a dims list, aligned with it (innermost = 1).
  (let ((acc 1) (out nil))
    (do ((p (reverse dims) (cdr p)))
        ((null p) out)
      (setq out (cons acc out))
      (setq acc (* acc (car p))))))

(defun linalg::%la-bcast-loop (%la-op %la-x %la-y od)
  ;; The general broadcast walk over the output shape od: out's flat row-major
  ;; index advances by 1 while each operand's flat index follows its stride-0-
  ;; padded strides through an odometer carry from the innermost axis out --
  ;; O(1) amortized per element, no per-element division. The result keeps the
  ;; FIRST operand's width, like the equal-shape mixed-width rule.
  (let* ((rdims (reverse od))
         (rsx (linalg::%la-bcast-strides (array-dimensions %la-x) od))
         (rsy (linalg::%la-bcast-strides (array-dimensions %la-y) od))
         (out (linalg::%la-make od 0.0 (linalg::%la-etype %la-x)))
         (n (array-total-size out))
         (idx (linalg::%la-zero-counters (length od)))
         (ox 0)
         (oy 0))
    (do ((k 0 (+ k 1)))
        ((>= k n) out)
      (setf (row-major-aref out k)
       (funcall %la-op (row-major-aref %la-x ox) (row-major-aref %la-y oy)))
      (do ((pc idx (cdr pc))
           (pd rdims (cdr pd))
           (psx rsx (cdr psx))
           (psy rsy (cdr psy))
           (carry t))
          ((or (null pc) (not carry)))
        (rplaca pc (+ (car pc) 1))
        (setq ox (+ ox (car psx)))
        (setq oy (+ oy (car psy)))
        (if (< (car pc) (car pd))
            (setq carry nil)
            (progn
              (rplaca pc 0)
              (setq ox (- ox (* (car pd) (car psx))))
              (setq oy (- oy (* (car pd) (car psy))))))))))

(defun linalg::%la-bcast (%la-op %la-x %la-y)
  ;; Applies the binary function %la-op elementwise, broadcasting a scalar
  ;; operand over the other operand's shape and two arrays of different shapes
  ;; by the numpy rules (%la-bcast-shape). Equal shapes keep the flat loop --
  ;; the path the --simd kernels mirror; a broadcast pair is an input those
  ;; kernels decline, so it always runs here. The parameters keep their %la-
  ;; names from when the compiled backends resolved a captured name against a
  ;; same-named user global (fixed 2026-07-03); the prefix is harmless and stays.
  (cond ((and (numberp %la-x) (numberp %la-y)) (funcall %la-op %la-x %la-y))
   ((numberp %la-x) (linalg:emap (lambda (v) (funcall %la-op %la-x v)) %la-y))
   ((numberp %la-y) (linalg:emap (lambda (v) (funcall %la-op v %la-y)) %la-x))
   ((equal (array-dimensions %la-x) (array-dimensions %la-y))
    (let ((n (array-total-size %la-x)) (out (linalg::%la-like %la-x)))
      (do ((k 0 (+ k 1)))
          ((>= k n) out)
        (setf (row-major-aref out k)
         (funcall %la-op (row-major-aref %la-x k) (row-major-aref %la-y k))))))
   (t (linalg::%la-bcast-loop %la-op %la-x %la-y
                              (linalg::%la-bcast-shape (array-dimensions %la-x)
                               (array-dimensions %la-y))))))

(defun linalg::%la-reduce (f a init)
  ;; Folds f over every element of a (row-major), starting from init.
  (let ((n (array-total-size a)) (acc init))
    (do ((k 0 (+ k 1)))
        ((>= k n) acc)
      (setq acc (funcall f acc (row-major-aref a k))))))

(defun linalg::%la-norm-axis (d axis)
  ;; Normalizes a possibly negative axis against the dims list d (numpy's
  ;; axis + rank rule) and errors when out of range.
  (let* ((rank (length d)) (ax (if (< axis 0) (+ axis rank) axis)))
    (unless (and (>= ax 0) (< ax rank)) (error "linalg: axis out of range"))
    ax))

(defun linalg::%la-head-size (d ax)
  ;; The product of the dims-list entries before axis ax.
  (let ((acc 1))
    (do ((p d (cdr p)) (k 0 (+ k 1)))
        ((>= k ax) acc)
      (setq acc (* acc (car p))))))

(defun linalg::%la-tail-size (d ax)
  ;; The product of the dims-list entries after axis ax.
  (let ((acc 1))
    (do ((p d (cdr p)) (k 0 (+ k 1)))
        ((null p) acc)
      (when (> k ax) (setq acc (* acc (car p)))))))

(defun linalg::%la-axis-shape (d ax keepdims)
  ;; The dims list with axis ax dropped -- or kept as extent 1 under keepdims.
  ;; nil (rank 0) when a vector's only axis is dropped: the caller returns the
  ;; reduced scalar itself in that case.
  (let ((out nil))
    (do ((p (reverse d) (cdr p)) (k (- (length d) 1) (- k 1)))
        ((null p) out)
      (cond ((/= k ax) (setq out (cons (car p) out)))
            (keepdims (setq out (cons 1 out)))))))

(defun linalg::%la-ones-shape (d)
  ;; A dims list of all 1s with d's length (numpy's keepdims shape of a full
  ;; no-axis reduction).
  (let ((out nil))
    (do ((p d (cdr p)))
        ((null p) out)
      (setq out (cons 1 out)))))

(defun linalg::%la-wrap-scalar (a val keepdims)
  ;; The keepdims wrapping of a full (no-axis) reduction: the scalar itself,
  ;; or under a non-nil keepdims an all-ones-shape array holding it (numpy).
  (if keepdims
      (linalg::%la-make (linalg::%la-ones-shape (array-dimensions a)) val
                        (linalg::%la-etype a))
      val))

(defun linalg::%la-fold-axis (a ax f init keepdims)
  ;; Reduces axis ax (already normalized) of a with the binary fold f over a
  ;; row-major outer x axis x inner walk: out[o, i] folds f over a[o, j, i].
  ;; init seeds the fold; a nil init seeds from the first element along the
  ;; axis instead (the amax/amin rule) and errors on an empty axis. The axis
  ;; is dropped from the result -- kept with extent 1 under keepdims -- and a
  ;; vector without keepdims reduces to the scalar itself (numpy). Any-rank
  ;; via the flat index (o * axlen + j) * inner + i.
  (let* ((d (array-dimensions a))
         (axlen (nth ax d))
         (inner (linalg::%la-tail-size d ax))
         (outer (linalg::%la-head-size d ax))
         (od (linalg::%la-axis-shape d ax keepdims)))
    (when (and (null init) (= axlen 0))
      (error "linalg: reduction of an empty axis"))
    (if (null od)
        (let ((acc (if init init (aref a 0))))
          (do ((j (if init 0 1) (+ j 1)))
              ((>= j axlen) acc)
            (setq acc (funcall f acc (aref a j)))))
        (let ((out (linalg::%la-make od 0.0 (linalg::%la-etype a))))
          (do ((o 0 (+ o 1)))
              ((>= o outer) out)
            (do ((i 0 (+ i 1)))
                ((>= i inner))
              (let ((base (+ (* o axlen inner) i)) (acc init))
                (unless acc (setq acc (row-major-aref a base)))
                (do ((j (if init 0 1) (+ j 1)))
                    ((>= j axlen))
                  (setq acc
                   (funcall f acc (row-major-aref a (+ base (* j inner))))))
                (setf (row-major-aref out (+ (* o inner) i)) acc))))))))

(defun linalg::%la-argfold-axis (a ax cmp)
  ;; The per-slice index of the first element winning the strict comparison
  ;; cmp along axis ax (already normalized); the axis is dropped. A vector
  ;; reduces to the integer index itself; higher ranks fill a packed DOUBLE
  ;; array of index values (linalg arrays have no integer width, and indices
  ;; are exact in a double). Errors on an empty axis.
  (let* ((d (array-dimensions a))
         (axlen (nth ax d))
         (inner (linalg::%la-tail-size d ax))
         (outer (linalg::%la-head-size d ax))
         (od (linalg::%la-axis-shape d ax nil)))
    (when (= axlen 0) (error "linalg: reduction of an empty axis"))
    (if (null od)
        (let ((best (aref a 0)) (bi 0))
          (do ((j 1 (+ j 1)))
              ((>= j axlen) bi)
            (when (funcall cmp (aref a j) best)
              (setq best (aref a j))
              (setq bi j))))
        (let ((out (linalg::%la-make od 0.0 'double-float)))
          (do ((o 0 (+ o 1)))
              ((>= o outer) out)
            (do ((i 0 (+ i 1)))
                ((>= i inner))
              (let* ((base (+ (* o axlen inner) i))
                     (best (row-major-aref a base))
                     (bi 0))
                (do ((j 1 (+ j 1)))
                    ((>= j axlen))
                  (let ((x (row-major-aref a (+ base (* j inner)))))
                    (when (funcall cmp x best)
                      (setq best x)
                      (setq bi j))))
                (setf (row-major-aref out (+ (* o inner) i)) bi))))))))

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
    (unless (= n (length v)) (error "linalg: dot expects equal-length vectors"))
    (let ((acc 0))
      (do ((i 0 (+ i 1)))
          ((>= i n) acc)
        (setq acc (+ acc (* (aref u i) (aref v i))))))))

(defun linalg::%la-dot-mv (a v)
  ;; Matrix times column vector -> vector.
  (let* ((d (array-dimensions a)) (n (car d)) (m (car (cdr d))))
    (unless (= m (length v)) (error "linalg: dot dimension mismatch"))
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
  (let* ((d (array-dimensions a)) (n (car d)) (m (car (cdr d))))
    (unless (= n (length v)) (error "linalg: dot dimension mismatch"))
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
    (unless (= m (car db)) (error "linalg: matmul inner dimensions differ"))
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

(defun linalg::%la-diff-1 (a ax)
  ;; One first-difference step along axis ax (already normalized): out[o, j, i]
  ;; = a[o, j+1, i] - a[o, j, i] over the row-major outer x axis x inner walk
  ;; (flat index (o * axlen + j) * inner + i), so every rank and every axis is
  ;; one triple loop. An axis of length 0 or 1 differences to length 0
  ;; (numpy's clamp, not an error).
  (let* ((d (array-dimensions a))
         (axlen (nth ax d))
         (w (max 0 (- axlen 1)))
         (inner (linalg::%la-tail-size d ax))
         (outer (linalg::%la-head-size d ax))
         (od
          (let ((acc nil))
            (do ((p d (cdr p)) (k 0 (+ k 1)))
                ((null p) (reverse acc))
              (setq acc (cons (if (= k ax) w (car p)) acc)))))
         (out (linalg::%la-make od 0.0 (linalg::%la-etype a))))
    (do ((o 0 (+ o 1)))
        ((>= o outer) out)
      (do ((i 0 (+ i 1)))
          ((>= i inner))
        (let ((src (+ (* o axlen inner) i)) (dst (+ (* o w inner) i)))
          (do ((j 0 (+ j 1)))
              ((>= j w))
            (setf (row-major-aref out (+ dst (* j inner)))
                  (- (row-major-aref a (+ src (* (+ j 1) inner)))
                     (row-major-aref a (+ src (* j inner)))))))))))

;; --- constructors ------------------------------------------------------------

(defun linalg:zeros (shape &key element-type)
  ;; A zero-filled vector (integer shape) or matrix (list shape). Double-float by
  ;; default; :element-type 'single-float builds a packed single-float (#f) result.
  (linalg::%la-make shape 0.0 element-type))

(defun linalg:ones (shape &key element-type)
  ;; A one-filled vector or matrix (double by default; :element-type
  ;; 'single-float for #f).
  (linalg::%la-make shape 1.0 element-type))

(defun linalg:full (shape value &key element-type)
  ;; A vector or matrix with every element set to value (double by default;
  ;; :element-type 'single-float for #f).
  (linalg::%la-make shape value element-type))

(defun linalg:zeros-like (a)
  ;; A zero-filled array with a's shape AND width (numpy np.zeros_like).
  (linalg::%la-like a))

(defun linalg:eye (n &key element-type)
  ;; The n-by-n identity matrix (double by default; :element-type 'single-float
  ;; for #f).
  (let ((m (linalg::%la-make (list n n) 0.0 element-type)))
    (do ((i 0 (+ i 1)))
        ((>= i n) m)
      (setf (aref m i i) 1))))

(defun linalg::%la-split-element-type (args)
  ;; Splits a positional-plus-keyword argument list into (positionals . element-type)
  ;; for the one numpy signature whose positional count varies (arange): the
  ;; :element-type pair may sit anywhere after the positionals; any other keyword
  ;; signals like an &key lambda list would.
  (let ((pos nil) (et nil))
    (do ((a args))
        ((null a) (cons (reverse pos) et))
      (cond ((eq (car a) :element-type)
             (when (null (cdr a)) (error "Odd number of keyword arguments"))
             (setq et (cadr a))
             (setq a (cddr a)))
            ((keywordp (car a)) (error "Unknown keyword argument: ~a" (car a)))
            ((not (numberp (car a)))
             (error
              "linalg: arange expects numbers (the element type is :element-type 'single-float)"))
            (t
             (setq pos (cons (car a) pos))
             (setq a (cdr a)))))))

(defun linalg:arange (&rest args)
  ;; (arange stop), (arange start stop), or (arange start stop step) -- plus an
  ;; optional :element-type keyword, numpy's arange([start,] stop[, step], dtype):
  ;; the vector of numbers from start (default 0) up to but excluding stop,
  ;; advancing by step (default 1; may be negative). Double-float by default;
  ;; :element-type 'single-float builds a packed single-float (#f) result.
  (let* ((split (linalg::%la-split-element-type args))
         (pos (car split))
         (k (length pos)))
    (when (or (< k 1) (> k 3))
      (error "linalg: arange takes 1 to 3 positional arguments"))
    (let* ((start (if (cdr pos) (car pos) 0))
           (stop (if (cdr pos) (cadr pos) (car pos)))
           (d (if (cddr pos) (caddr pos) 1))
           (count (ceiling (/ (- stop start) d)))
           (n (max 0 count))
           (out (linalg::%la-make n 0.0 (cdr split))))
      (do ((i 0 (+ i 1)) (x start (+ x d)))
          ((>= i n) out)
        (setf (aref out i) x)))))

(defun linalg:linspace (start stop n &key element-type)
  ;; The vector of n evenly spaced numbers from start to stop inclusive (double by
  ;; default; :element-type 'single-float for a packed single-float (#f) result).
  (let ((out (linalg::%la-make n 0.0 element-type)))
    (if (= n 1)
        (progn
          (setf (aref out 0) start)
          out)
        (let ((step (/ (- stop start) (- n 1))))
          (do ((i 0 (+ i 1)))
              ((>= i n) out)
            (setf (aref out i) (+ start (* step i))))))))

(defun linalg:from-list (lst &key element-type)
  ;; A vector from a flat list, or a matrix from a list of equal-length rows
  ;; (double by default; :element-type 'single-float for a packed single-float
  ;; (#f) result).
  (if (consp (car lst))
      (let* ((r (length lst))
             (c (length (car lst)))
             (m (linalg::%la-make (list r c) 0.0 element-type)))
        (do ((rows lst (cdr rows)) (i 0 (+ i 1)))
            ((null rows) m)
          (do ((cells (car rows) (cdr cells)) (j 0 (+ j 1)))
              ((null cells))
            (setf (aref m i j) (car cells)))))
      (let* ((n (length lst)) (v (linalg::%la-make n 0.0 element-type)))
        (do ((cells lst (cdr cells)) (i 0 (+ i 1)))
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

(defun linalg:ndim (a)
  ;; The number of dimensions (numpy's np.ndim): 0 for a plain number, else the
  ;; array's rank.
  (if (numberp a) 0 (length (array-dimensions a))))

(defun linalg:size (a)
  ;; The total element count.
  (array-total-size a))

(defun linalg::%la-infer-shape (shape total)
  ;; Resolves a -1 extent in a reshape shape (at most one, numpy style)
  ;; against the total element count; a bare -1 flattens.
  (if (numberp shape)
      (if (= shape -1) total shape)
      (let ((known 1) (minus 0))
        (do ((p shape (cdr p)))
            ((null p))
          (if (= (car p) -1)
              (setq minus (+ minus 1))
              (setq known (* known (car p)))))
        (cond ((= minus 0) shape)
              ((> minus 1) (error "linalg: reshape allows at most one -1"))
              ((or (= known 0) (/= (mod total known) 0))
               (error "linalg: reshape size mismatch"))
              (t (let ((inferred (/ total known)) (out nil))
                   (do ((p (reverse shape) (cdr p)))
                       ((null p) out)
                     (setq out
                      (cons (if (= (car p) -1) inferred (car p)) out)))))))))

(defun linalg:reshape (a shape)
  ;; A fresh array with the given shape and the same row-major elements (same
  ;; width as a: a #f reshapes to #f, a #d to #d). One extent may be -1 and is
  ;; inferred from the element count (numpy); a bare -1 shape flattens.
  (let* ((n (array-total-size a))
         (out
          (linalg::%la-make (linalg::%la-infer-shape shape n) 0.0
                            (linalg::%la-etype a))))
    (unless (= n (array-total-size out))
      (error "linalg: reshape size mismatch"))
    (do ((k 0 (+ k 1)))
        ((>= k n) out)
      (setf (row-major-aref out k) (row-major-aref a k)))))

(defun linalg:flatten (a)
  ;; The elements of a as a fresh rank-1 vector (row-major).
  (linalg:reshape a (linalg:size a)))

(defun linalg::%la-transpose-axes (a axes)
  ;; The rank-n axis permutation behind linalg:transpose's axes form (numpy
  ;; x.transpose(0 3 1 2)): output axis k draws from input axis (nth k axes),
  ;; so out-dims[k] = dims[axes[k]] and the source flat index follows the
  ;; permuted row-major strides through the %la-bcast-loop odometer walk (no
  ;; per-element division).
  (let* ((d (array-dimensions a))
         (rank (length d))
         (sx (linalg::%la-strides d))
         (seen (linalg::%la-zero-counters rank))
         (od nil)
         (os nil))
    (unless (= (length axes) rank)
      (error "linalg: transpose axes must be a permutation of the axes"))
    (do ((p (reverse axes) (cdr p)))
        ((null p))
      (let ((ax (car p)))
        (unless (and (numberp ax) (>= ax 0) (< ax rank))
          (error "linalg: transpose axes must be a permutation of the axes"))
        (let ((cell (nthcdr ax seen)))
          (unless (= (car cell) 0)
            (error "linalg: transpose axes must be a permutation of the axes"))
          (rplaca cell 1))
        (setq od (cons (nth ax d) od))
        (setq os (cons (nth ax sx) os))))
    (let* ((out (linalg::%la-make od 0.0 (linalg::%la-etype a)))
           (n (array-total-size out))
           (rdims (reverse od))
           (rstrides (reverse os))
           (idx (linalg::%la-zero-counters rank))
           (src 0))
      (do ((k 0 (+ k 1)))
          ((>= k n) out)
        (setf (row-major-aref out k) (row-major-aref a src))
        (do ((pc idx (cdr pc))
             (pd rdims (cdr pd))
             (ps rstrides (cdr ps))
             (carry t))
            ((or (null pc) (not carry)))
          (rplaca pc (+ (car pc) 1))
          (setq src (+ src (car ps)))
          (if (< (car pc) (car pd))
              (setq carry nil)
              (progn
                (rplaca pc 0)
                (setq src (- src (* (car pd) (car ps)))))))))))

(defun linalg:transpose (a &optional axes)
  ;; With no axes, the transpose of a matrix; a vector is returned unchanged
  ;; (like numpy). With an axes list (numpy x.transpose(1 0 2)), the rank-n
  ;; axis permutation: out-dims[k] = dims[axes[k]]. Both call forms are
  ;; --simd-intercepted; a
  ;; non-permutation axes argument declines to this defun's error.
  (if axes
      (linalg::%la-transpose-axes a axes)
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
            a))))

(defun linalg:pad (a pads)
  ;; Zero padding (numpy np.pad's default constant-0 mode): pads is a list of
  ;; (before after) pairs, one per axis -- or a single non-negative integer
  ;; applied to both sides of every axis. Returns a fresh array of a's width;
  ;; the input is copied into the interior through an odometer walk like
  ;; %la-bcast-loop.
  (let* ((d (array-dimensions a))
         (rank (length d))
         (pp
          (if (numberp pads)
              (let ((out nil))
                (do ((k 0 (+ k 1)))
                    ((>= k rank) out)
                  (setq out (cons (list pads pads) out))))
              pads)))
    (unless (= (length pp) rank)
      (error "linalg: pad expects one (before after) pair per axis"))
    (let ((od nil))
      (do ((pd (reverse d) (cdr pd)) (pq (reverse pp) (cdr pq)))
          ((null pd))
        (let ((b (car (car pq))) (f (car (cdr (car pq)))))
          (unless (and (numberp b) (numberp f) (>= b 0) (>= f 0))
            (error "linalg: pad widths must be non-negative"))
          (setq od (cons (+ (car pd) b f) od))))
      (let* ((out (linalg::%la-make od 0.0 (linalg::%la-etype a)))
             (so (linalg::%la-strides od))
             (n (array-total-size a))
             (rdims (reverse d))
             (rstrides (reverse so))
             (idx (linalg::%la-zero-counters rank))
             (dst 0))
        ;; Start dst at the all-before corner of the output.
        (do ((pq pp (cdr pq)) (ps so (cdr ps)))
            ((null pq))
          (setq dst (+ dst (* (car (car pq)) (car ps)))))
        (do ((k 0 (+ k 1)))
            ((>= k n) out)
          (setf (row-major-aref out dst) (row-major-aref a k))
          (do ((pc idx (cdr pc))
               (pd rdims (cdr pd))
               (ps rstrides (cdr ps))
               (carry t))
              ((or (null pc) (not carry)))
            (rplaca pc (+ (car pc) 1))
            (setq dst (+ dst (car ps)))
            (if (< (car pc) (car pd))
                (setq carry nil)
                (progn
                  (rplaca pc 0)
                  (setq dst (- dst (* (car pd) (car ps))))))))))))

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

;; The CL operator spellings of the four elementwise kernels above. They are
;; n-ary exactly like cl:+ / cl:- / cl:* / cl:/ -- left-folded over add/sub/mul/
;; div -- so (linalg:+ a b c) reads the way arithmetic reads and the degenerate
;; arities keep their CL meaning: no argument is the identity (0 / 1), a single
;; argument to + and * is itself, and a single argument to - and / is the
;; negation / reciprocal. Each fold step is a literal linalg:add/sub/mul/div
;; call, so the --simd interceptors still see the kernel they match.

(defun linalg:+ (&rest args)
  ;; n-ary elementwise sum: (linalg:+) is 0, otherwise a left fold of linalg:add.
  (if (null args)
      0
      (do ((acc (car args) (linalg:add acc (car rest)))
           (rest (cdr args) (cdr rest)))
          ((null rest) acc))))

(defun linalg:- (a &rest args)
  ;; n-ary elementwise difference: one argument negates, otherwise a left fold
  ;; of linalg:sub.
  (if (null args)
      (linalg:sub 0 a)
      (do ((acc a (linalg:sub acc (car rest))) (rest args (cdr rest)))
          ((null rest) acc))))

(defun linalg:* (&rest args)
  ;; n-ary elementwise (Hadamard) product: (linalg:*) is 1, otherwise a left
  ;; fold of linalg:mul. NOT the matrix product -- that is linalg:matmul.
  (if (null args)
      1
      (do ((acc (car args) (linalg:mul acc (car rest)))
           (rest (cdr args) (cdr rest)))
          ((null rest) acc))))

(defun linalg:/ (a &rest args)
  ;; n-ary elementwise quotient: one argument is the reciprocal, otherwise a
  ;; left fold of linalg:div.
  (if (null args)
      (linalg:div 1 a)
      (do ((acc a (linalg:div acc (car rest))) (rest args (cdr rest)))
          ((null rest) acc))))

(defun linalg:emap (f a)
  ;; A fresh array with f applied to every element of a.
  (let ((n (array-total-size a)) (out (linalg::%la-like a)))
    (do ((k 0 (+ k 1)))
        ((>= k n) out)
      (setf (row-major-aref out k) (funcall f (row-major-aref a k))))))

;; --- named elementwise ufuncs (numpy parity) ----------------------------------
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

;; --- comparison-select ufuncs (numpy parity) ----------------------------------
;; Defined by the strict comparison select, NOT a min/max primitive: the second
;; operand wins whenever the comparison is false -- ties (a -0.0 / 0.0 pair takes
;; the second) and unordered NaN comparisons included. Same rule as linalg:amax;
;; > and < agree bit-for-bit across backends.

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
        (t
         (let ((ra (cdr (array-dimensions a))) (rb (cdr (array-dimensions b))))
           (cond ((and (null ra) (null rb)) (linalg::%la-dot-vv a b))
                 ((and ra (null rb)) (linalg::%la-dot-mv a b))
                 ((and (null ra) rb) (linalg::%la-dot-vm a b))
                 (t (linalg::%la-matmul a b)))))))

(defun linalg:matmul (a b)
  ;; Matrix product (also matrix . vector); rejects scalar operands.
  (when (or (numberp a) (numberp b)) (error "linalg: matmul expects arrays"))
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

(defun linalg:sum (a &key axis keepdims)
  ;; With no :axis, the sum of every element: a scalar, or under a non-nil
  ;; :keepdims an all-ones-shape array holding it (numpy). With an integer
  ;; :axis (negative counts from the end), the sums along that axis: the axis
  ;; is dropped from the result -- kept with extent 1 under keepdims -- and a
  ;; vector without keepdims reduces to the scalar itself.
  (if (null axis)
      (linalg::%la-wrap-scalar a (linalg::%la-reduce #'+ a 0) keepdims)
      (linalg::%la-fold-axis a (linalg::%la-norm-axis (array-dimensions a) axis)
                             #'+ 0 keepdims)))

(defun linalg:mean (a &key axis keepdims)
  ;; The arithmetic mean, of every element (no :axis) or along an axis (the
  ;; same :axis/:keepdims rules as linalg:sum). The no-axis total stays a
  ;; 1-argument linalg:sum call so that call site keeps its --simd kernel, and
  ;; the axis total spells its keywords literally for the same reason.
  (if (null axis)
      (linalg::%la-wrap-scalar a (/ (linalg:sum a) (linalg:size a)) keepdims)
      (let ((ax (linalg::%la-norm-axis (array-dimensions a) axis)))
        (linalg:div (linalg:sum a :axis ax :keepdims keepdims)
                    (nth ax (array-dimensions a))))))

(defun linalg:amax (a &key axis keepdims)
  ;; The largest element, of the whole array (no :axis) or along an integer
  ;; :axis (the same :axis/:keepdims rules as linalg:sum; strict-comparison
  ;; fold, so the first element wins ties and a NaN never replaces the seed).
  ;; Errors on an empty array or axis.
  (if (null axis)
      (let ((n (array-total-size a)))
        (when (= n 0) (error "linalg: amax of an empty array"))
        (let ((best (row-major-aref a 0)))
          (do ((k 1 (+ k 1)))
              ((>= k n) (linalg::%la-wrap-scalar a best keepdims))
            (let ((x (row-major-aref a k))) (when (> x best) (setq best x))))))
      (linalg::%la-fold-axis a (linalg::%la-norm-axis (array-dimensions a) axis)
                             (lambda (acc x) (if (> x acc) x acc)) nil
                             keepdims)))

(defun linalg:amin (a &key axis keepdims)
  ;; The smallest element, of the whole array (no :axis) or along an integer
  ;; :axis; the linalg:amax rules with the comparison flipped.
  (if (null axis)
      (let ((n (array-total-size a)))
        (when (= n 0) (error "linalg: amin of an empty array"))
        (let ((best (row-major-aref a 0)))
          (do ((k 1 (+ k 1)))
              ((>= k n) (linalg::%la-wrap-scalar a best keepdims))
            (let ((x (row-major-aref a k))) (when (< x best) (setq best x))))))
      (linalg::%la-fold-axis a (linalg::%la-norm-axis (array-dimensions a) axis)
                             (lambda (acc x) (if (< x acc) x acc)) nil
                             keepdims)))

(defun linalg:argmax (v &key axis)
  ;; With no :axis: the index of the largest element of a vector (first on
  ;; ties). With an integer :axis (negative counts from the end): the
  ;; per-slice indices along that axis, the axis dropped; a rank >= 2 result
  ;; is a packed DOUBLE array of index values (linalg arrays have no integer
  ;; width; (= 3.0 3) still holds for comparisons), a vector reduces to the
  ;; integer index itself.
  (if (null axis)
      (let ((n (length v)))
        (when (= n 0) (error "linalg: argmax of an empty vector"))
        (let ((best (aref v 0)) (bi 0))
          (do ((i 1 (+ i 1)))
              ((>= i n) bi)
            (when (> (aref v i) best)
              (setq best (aref v i))
              (setq bi i)))))
      (linalg::%la-argfold-axis v
       (linalg::%la-norm-axis (array-dimensions v) axis) (function >))))

(defun linalg:argmin (v &key axis)
  ;; The index of the smallest element; the linalg:argmax rules with the
  ;; comparison flipped.
  (if (null axis)
      (let ((n (length v)))
        (when (= n 0) (error "linalg: argmin of an empty vector"))
        (let ((best (aref v 0)) (bi 0))
          (do ((i 1 (+ i 1)))
              ((>= i n) bi)
            (when (< (aref v i) best)
              (setq best (aref v i))
              (setq bi i)))))
      (linalg::%la-argfold-axis v
       (linalg::%la-norm-axis (array-dimensions v) axis) (function <))))

(defun linalg:norm (a)
  ;; The Euclidean (L2 / Frobenius) norm.
  (sqrt (linalg:sum (linalg:emap (lambda (x) (* x x)) a))))

(defun linalg:trace (a)
  ;; The sum of the main-diagonal elements of a square matrix.
  (let ((n (linalg::%la-square-size a)) (acc 0))
    (do ((i 0 (+ i 1)))
        ((>= i n) acc)
      (setq acc (+ acc (aref a i i))))))

;; --- calculus (numpy diff / gradient) -----------------------------------------

(defun linalg:diff (a &key (n 1) (axis -1))
  ;; The n-th discrete difference along :axis (numpy np.diff, default the last
  ;; axis; negative counts from the end): out[..., i, ...] = a[..., i+1, ...]
  ;; - a[..., i, ...], applied :n times (default 1); each step shortens the
  ;; axis by one (clamped at 0, like numpy). Works for any rank; the result is
  ;; a fresh packed array of a's width (so :n 0 returns a packed COPY, where
  ;; numpy returns the input itself).
  (let ((ax (linalg::%la-norm-axis (array-dimensions a) axis)))
    (when (< n 0) (error "linalg: diff order must be non-negative"))
    (if (= n 0)
        (linalg::%la-copy a)
        (let ((out (linalg::%la-diff-1 a ax)))
          (do ((k 1 (+ k 1)))
              ((>= k n) out)
            (setq out (linalg::%la-diff-1 out ax)))))))

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
    (when (cdr d) (error "linalg: gradient expects a vector"))
    (let ((n (car d)))
      (when (< n 2) (error "linalg: gradient needs at least 2 samples"))
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
                (error
                 "linalg: gradient coordinates must match the sample length"))
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
    (let ((m (linalg::%la-copy a)) (det 1))
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
        (when (null p) (error "linalg: inv of a singular matrix"))
        (unless (= p col) (linalg::%la-swap-rows m p col w))
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
      (let ((n (array-total-size a)) (ok t))
        (do ((k 0 (+ k 1)))
            ((or (>= k n) (null ok)) ok)
          (let ((x (row-major-aref a k)) (y (row-major-aref b k)))
            (unless (if (and (numberp x) (numberp y)) (= x y) (equal x y))
              (setq ok nil)))))
      nil))

;; --- elementwise comparisons (0/1 masks) ---------------------------------------
;; numpy's ==, >, >=, <, <= produce boolean arrays; here each is a 0.0/1.0
;; mask of the first array operand's width (multiply by the mask where numpy
;; would boolean-index). Scalar operands and broadcasting follow %la-bcast.

(defun linalg:equal (a b)
  ;; Elementwise numeric equality as a 0.0/1.0 mask; either operand may be a
  ;; scalar. (One boolean for the whole array is linalg:array-equal.)
  (linalg::%la-bcast (lambda (x y) (if (= x y) 1 0)) a b))

(defun linalg:greater (a b)
  ;; Elementwise a > b as a 0.0/1.0 mask; either operand may be a scalar.
  (linalg::%la-bcast (lambda (x y) (if (> x y) 1 0)) a b))

(defun linalg:greater-equal (a b)
  ;; Elementwise a >= b as a 0.0/1.0 mask; either operand may be a scalar.
  (linalg::%la-bcast (lambda (x y) (if (>= x y) 1 0)) a b))

(defun linalg:less (a b)
  ;; Elementwise a < b as a 0.0/1.0 mask; either operand may be a scalar.
  (linalg::%la-bcast (lambda (x y) (if (< x y) 1 0)) a b))

(defun linalg:less-equal (a b)
  ;; Elementwise a <= b as a 0.0/1.0 mask; either operand may be a scalar.
  (linalg::%la-bcast (lambda (x y) (if (<= x y) 1 0)) a b))

;; --- indexing / selection ------------------------------------------------------

(defun linalg:take-rows (a idx)
  ;; The axis-0 slices of a selected by the index vector idx (numpy's
  ;; x[batch-mask] / np.take(a, idx, axis=0)), as a fresh array of a's width.
  ;; Whole slabs are copied row-major, so any rank >= 1 works (a rank-4 batch
  ;; extraction included); index values are truncated to integers, and the
  ;; same index may appear more than once.
  (let* ((d (array-dimensions a))
         (slab (linalg::%la-tail-size d 0))
         (m (length idx))
         (out (linalg::%la-make (cons m (cdr d)) 0.0 (linalg::%la-etype a))))
    (do ((i 0 (+ i 1)))
        ((>= i m) out)
      (let ((src (* (truncate (aref idx i)) slab)) (dst (* i slab)))
        (do ((k 0 (+ k 1)))
            ((>= k slab))
          (setf (row-major-aref out (+ dst k))
                (row-major-aref a (+ src k))))))))

(defun linalg:row (a i)
  ;; The axis-0 slice i of a with axis 0 DROPPED (numpy's x[i] integer
  ;; indexing), as a fresh array of a's width: a matrix yields the row vector,
  ;; a rank-4 batch yields the rank-3 sample. This is the one-slice sibling of
  ;; take-rows, which keeps axis 0 (numpy's x[[i]]); use aref for an element.
  (let ((d (array-dimensions a)))
    (unless (cdr d)
      (error "linalg: row expects rank >= 2 (use aref on a vector)"))
    (let* ((slab (linalg::%la-tail-size d 0))
           (src (* (truncate i) slab))
           (out (linalg::%la-make (cdr d) 0.0 (linalg::%la-etype a))))
      (do ((k 0 (+ k 1)))
          ((>= k slab) out)
        (setf (row-major-aref out k) (row-major-aref a (+ src k)))))))

(defun linalg:gather (a idx)
  ;; The per-row elements a[i, idx[i]] of a matrix (numpy's
  ;; y[np.arange(n), t] fancy-indexing idiom) as a vector of a's width;
  ;; index values are truncated to integers.
  (let ((d (array-dimensions a)))
    (unless (and (cdr d) (null (cdr (cdr d))))
      (error "linalg: gather expects a matrix"))
    (let ((n (car d)))
      (unless (= n (length idx))
        (error "linalg: gather index length must match the rows"))
      (let ((out (linalg::%la-make n 0.0 (linalg::%la-etype a))))
        (do ((i 0 (+ i 1)))
            ((>= i n) out)
          (setf (aref out i) (aref a i (truncate (aref idx i)))))))))

(defun linalg:one-hot (idx n &key element-type)
  ;; The (length idx) x n one-hot matrix: row i holds 1.0 in column idx[i]
  ;; (truncated to an integer) and 0.0 elsewhere (double by default;
  ;; :element-type 'single-float for #f).
  (let* ((m (length idx)) (out (linalg::%la-make (list m n) 0.0 element-type)))
    (do ((i 0 (+ i 1)))
        ((>= i m) out)
      (setf (aref out i (truncate (aref idx i))) 1))))

;; --- CNN window unfolding (im2col / col2im) ------------------------------------
;; Internal rank-4 helpers behind the convolution examples (Deep Learning from
;; Scratch, common/util.py): numpy has no im2col either, so these stay %la-
;; internal rather than exported API. Both are direct index arithmetic --
;; equivalent to the book's pad + strided-slice + 6-D transpose composition,
;; without materializing the scratch tensors.

(defun linalg::%la-im2col (x fh fw stride pad)
  ;; Unfolds the rank-4 NCHW array x into the (N*out-h*out-w, C*fh*fw) matrix
  ;; whose row (n, oh, ow) holds the fh x fw window of every channel at that
  ;; output position: column index = (c, fy, fx). A window element that falls
  ;; in the zero padding stays 0.0. Width follows x.
  (let* ((d (array-dimensions x))
         (n (car d))
         (c (car (cdr d)))
         (h (car (cdr (cdr d))))
         (w (car (cdr (cdr (cdr d)))))
         (oh (+ 1 (floor (- (+ h (* 2 pad)) fh) stride)))
         (ow (+ 1 (floor (- (+ w (* 2 pad)) fw) stride)))
         (out
          (linalg::%la-make (list (* n oh ow) (* c fh fw)) 0.0
                            (linalg::%la-etype x)))
         (dst 0))
    (do ((ni 0 (+ ni 1)))
        ((>= ni n) out)
      (do ((yo 0 (+ yo 1)))
          ((>= yo oh))
        (do ((xo 0 (+ xo 1)))
            ((>= xo ow))
          (do ((ci 0 (+ ci 1)))
              ((>= ci c))
            (do ((fy 0 (+ fy 1)))
                ((>= fy fh))
              (let ((iy (- (+ (* yo stride) fy) pad)))
                (if (and (>= iy 0) (< iy h))
                    (let ((base (* (+ (* (+ (* ni c) ci) h) iy) w))
                          (ix0 (- (* xo stride) pad)))
                      (do ((fx 0 (+ fx 1)))
                          ((>= fx fw))
                        (let ((ix (+ ix0 fx)))
                          (when (and (>= ix 0) (< ix w))
                            (setf (row-major-aref out dst)
                                  (row-major-aref x (+ base ix))))
                          (setq dst (+ dst 1)))))
                    ;; The whole filter row fell in the padding: skip it.
                    (setq dst (+ dst fw)))))))))))

(defun linalg::%la-col2im (col dims fh fw stride pad)
  ;; The im2col adjoint: scatter-ADDS the (N*out-h*out-w, C*fh*fw) matrix col
  ;; back into a fresh zero rank-4 NCHW array of the given dims (overlapping
  ;; windows accumulate, the convolution backward pass); elements that fell
  ;; in the zero padding are dropped. Width follows col.
  (let* ((n (car dims))
         (c (car (cdr dims)))
         (h (car (cdr (cdr dims))))
         (w (car (cdr (cdr (cdr dims)))))
         (oh (+ 1 (floor (- (+ h (* 2 pad)) fh) stride)))
         (ow (+ 1 (floor (- (+ w (* 2 pad)) fw) stride)))
         (img (linalg::%la-make dims 0.0 (linalg::%la-etype col)))
         (src 0))
    (do ((ni 0 (+ ni 1)))
        ((>= ni n) img)
      (do ((yo 0 (+ yo 1)))
          ((>= yo oh))
        (do ((xo 0 (+ xo 1)))
            ((>= xo ow))
          (do ((ci 0 (+ ci 1)))
              ((>= ci c))
            (do ((fy 0 (+ fy 1)))
                ((>= fy fh))
              (let ((iy (- (+ (* yo stride) fy) pad)))
                (if (and (>= iy 0) (< iy h))
                    (let ((base (* (+ (* (+ (* ni c) ci) h) iy) w))
                          (ix0 (- (* xo stride) pad)))
                      (do ((fx 0 (+ fx 1)))
                          ((>= fx fw))
                        (let ((ix (+ ix0 fx)))
                          (when (and (>= ix 0) (< ix w))
                            (setf (row-major-aref img (+ base ix))
                                  (+ (row-major-aref img (+ base ix))
                                     (row-major-aref col src))))
                          (setq src (+ src 1)))))
                    (setq src (+ src fw)))))))))))

;; --- random numbers (the np.random analog; seeded, backend-identical) ---------
;; A Wichmann-Hill generator: three small multiplicative congruential
;; generators combined into one uniform double. Every intermediate stays
;; below 2^23 -- inside the WASM i31 integer range -- and each draw is exact
;; integer arithmetic plus IEEE +-*/ on exact operands, so a seeded sequence
;; is bit-identical on every backend (period ~6.95e12). Gaussians use
;; Irwin-Hall (the sum of 12 uniforms minus 6), NOT Box-Muller: log/cos are
;; polynomial approximations on WASM and would break the cross-backend
;; identity, while +/- cannot. The tails clip at +/- 6 sigma -- fine for
;; weight initialization, but not a distribution-exact np.random.randn.

(defparameter linalg::%la-rng-s1 100)

(defparameter linalg::%la-rng-s2 200)

(defparameter linalg::%la-rng-s3 300)

(defun linalg::%la-rng-next ()
  ;; The next uniform double in [0, 1).
  (setq linalg::%la-rng-s1 (mod (* 171 linalg::%la-rng-s1) 30269))
  (setq linalg::%la-rng-s2 (mod (* 172 linalg::%la-rng-s2) 30307))
  (setq linalg::%la-rng-s3 (mod (* 170 linalg::%la-rng-s3) 30323))
  (let ((u
         (+ (/ linalg::%la-rng-s1 30269.0) (/ linalg::%la-rng-s2 30307.0)
            (/ linalg::%la-rng-s3 30323.0))))
    ;; frac(u) for u in [0, 3), by compares only (no float mod needed).
    (if (>= u 2.0) (- u 2.0) (if (>= u 1.0) (- u 1.0) u))))

(defun linalg::%la-rng-int (n)
  ;; A uniform integer in [0, n).
  (let ((i (floor (* (linalg::%la-rng-next) n)))) (if (>= i n) (- n 1) i)))

(defun linalg:seed (n)
  ;; Resets the generator deterministically from a non-negative integer seed,
  ;; then discards a few draws so nearby seeds decorrelate. Returns n. The
  ;; same seed reproduces the same rand/randn/uniform/choice/permutation
  ;; sequence on every backend.
  (setq linalg::%la-rng-s1 (+ 1 (mod n 30268)))
  (setq linalg::%la-rng-s2 (+ 1 (mod (+ n 12345) 30306)))
  (setq linalg::%la-rng-s3 (+ 1 (mod (+ n 6789) 30322)))
  (do ((k 0 (+ k 1)))
      ((>= k 10))
    (linalg::%la-rng-next))
  n)

(defun linalg:rand (shape &key element-type)
  ;; An array of uniform [0, 1) draws (np.random.rand, but with a shape
  ;; designator like linalg:zeros; double by default, :element-type
  ;; 'single-float for #f).
  (let* ((out (linalg::%la-make shape 0.0 element-type))
         (n (array-total-size out)))
    (do ((k 0 (+ k 1)))
        ((>= k n) out)
      (setf (row-major-aref out k) (linalg::%la-rng-next)))))

(defun linalg:randn (shape &key element-type)
  ;; An array of standard-normal draws via Irwin-Hall (np.random.randn, but
  ;; with a shape designator; see the section comment for the distribution
  ;; caveat).
  (let* ((out (linalg::%la-make shape 0.0 element-type))
         (n (array-total-size out)))
    (do ((k 0 (+ k 1)))
        ((>= k n) out)
      (let ((acc 0.0))
        (do ((j 0 (+ j 1)))
            ((>= j 12))
          (setq acc (+ acc (linalg::%la-rng-next))))
        (setf (row-major-aref out k) (- acc 6.0))))))

(defun linalg:uniform (lo hi shape &key element-type)
  ;; An array of uniform draws in [lo, hi) (np.random.uniform, but with a
  ;; required shape designator).
  (let* ((out (linalg::%la-make shape 0.0 element-type))
         (n (array-total-size out))
         (span (- hi lo)))
    (do ((k 0 (+ k 1)))
        ((>= k n) out)
      (setf (row-major-aref out k) (+ lo (* span (linalg::%la-rng-next)))))))

(defun linalg:choice (n size)
  ;; size uniform indices in [0, n), WITH replacement (np.random.choice's
  ;; default for an integer argument): a packed double vector of integer
  ;; values, the mini-batch sampling idiom.
  (let ((out (linalg::%la-make size 0.0 nil)))
    (do ((k 0 (+ k 1)))
        ((>= k size) out)
      (setf (aref out k) (linalg::%la-rng-int n)))))

(defun linalg:permutation (n)
  ;; The integers 0..n-1 in a Fisher-Yates shuffle (np.random.permutation of
  ;; an integer): a packed double vector.
  (let ((out (linalg::%la-make n 0.0 nil)))
    (do ((i 0 (+ i 1)))
        ((>= i n))
      (setf (aref out i) i))
    (do ((i (- n 1) (- i 1)))
        ((< i 1) out)
      (let* ((j (linalg::%la-rng-int (+ i 1))) (tmp (aref out i)))
        (setf (aref out i) (aref out j))
        (setf (aref out j) tmp)))))
