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

;; --- rank-N helpers (batched matmul, joins, strided gathers) ------------------

(defun linalg::%la-drop-last (d n)
  ;; The dims list d without its last n entries.
  (let ((keep (- (length d) n)) (out nil))
    (do ((p d (cdr p)) (k 0 (+ k 1)))
        ((>= k keep) (reverse out))
      (setq out (cons (car p) out)))))

(defun linalg::%la-from-size (d ax)
  ;; The product of the dims-list entries from index ax ONWARD (the complement
  ;; of %la-head-size, which stops before ax); 1 for an empty list.
  (let ((acc 1))
    (do ((p d (cdr p)) (k 0 (+ k 1)))
        ((null p) acc)
      (when (>= k ax) (setq acc (* acc (car p)))))))

(defun linalg::%la-insert-axis (d ax n)
  ;; The dims list d with a new axis of extent n inserted at index ax; an ax of
  ;; (length d) appends it.
  (let ((out nil))
    (do ((p d (cdr p)) (k 0 (+ k 1)))
        ((null p) (reverse (if (= k ax) (cons n out) out)))
      (when (= k ax) (setq out (cons n out)))
      (setq out (cons (car p) out)))))

(defun linalg::%la-replace-axis (d ax n)
  ;; The dims list d with the extent at index ax replaced by n.
  (let ((out nil))
    (do ((p d (cdr p)) (k 0 (+ k 1)))
        ((null p) (reverse out))
      (setq out (cons (if (= k ax) n (car p)) out)))))

(defun linalg::%la-gather-strided (a od rs base single)
  ;; A fresh od-shaped array -- single-float when single is non-nil, double
  ;; otherwise -- filled by walking a's flat row-major index from base,
  ;; advancing by the INNERMOST-FIRST strides rs through the same odometer
  ;; carry %la-bcast-loop uses -- O(1) amortized per element, no per-element
  ;; division. Every strided read in the library (broadcast-to, slice) is this
  ;; one walk, which is why it is an intercepted member of the --simd seam: the
  ;; width rides as a flag rather than an element-type symbol so a kernel on
  ;; any backend can read it without a symbol comparison.
  (let* ((out (linalg::%la-make od 0.0 (if single 'single-float nil)))
         (n (array-total-size out))
         (rdims (reverse od))
         (idx (linalg::%la-zero-counters (length od)))
         (src base))
    (do ((k 0 (+ k 1)))
        ((>= k n) out)
      (setf (row-major-aref out k) (row-major-aref a src))
      (do ((pc idx (cdr pc)) (pd rdims (cdr pd)) (ps rs (cdr ps)) (carry t))
          ((or (null pc) (not carry)))
        (rplaca pc (+ (car pc) 1))
        (setq src (+ src (car ps)))
        (if (< (car pc) (car pd))
            (setq carry nil)
            (progn
              (rplaca pc 0)
              (setq src (- src (* (car pd) (car ps))))))))))

(defun linalg::%la-broadcast-to (a od)
  ;; a materialized at the broadcast shape od (numpy np.broadcast_to, but a
  ;; COPY -- every linalg result is a fresh array): a stretched axis re-reads
  ;; the same element through its stride-0 entry. a's shape must already
  ;; broadcast to od (the caller computed od from it).
  (linalg::%la-gather-strided a od
   (linalg::%la-bcast-strides (array-dimensions a) od) 0
   (eq (linalg::%la-etype a) 'single-float)))

(defun linalg::%la-batch-shape (dx dy)
  ;; The broadcast shape of two BATCH dims lists (a stacked matmul's leading
  ;; axes): the %la-bcast-shape rule with matmul's own error message.
  (let ((out nil))
    (do ((px (reverse dx) (cdr px)) (py (reverse dy) (cdr py)))
        ((and (null px) (null py)) out)
      (let ((a (if px (car px) 1)) (b (if py (car py) 1)))
        (unless (or (= a b) (= a 1) (= b 1))
          (error "linalg: matmul batch dimensions do not broadcast"))
        (setq out (cons (max a b) out))))))

(defun linalg::%la-batch-strides (d od base)
  ;; Row-major strides of the batch dims d aligned to the broadcast batch shape
  ;; od, INNERMOST-FIRST, with 0 on every stretched axis (extent 1 or missing).
  ;; base is the size of the trailing matrix -- the stride of the innermost
  ;; batch axis -- which is what separates this from %la-bcast-strides.
  (let ((acc base) (out nil))
    (do ((pd (reverse d) (cdr pd)) (po (reverse od) (cdr po)))
        ((null po) (reverse out))
      (let ((n (if pd (car pd) 1)))
        (setq out (cons (if (= n 1) 0 acc) out))
        (setq acc (* acc n))))))

(defun linalg::%la-matmul-nd (a b)
  ;; The STACKED matrix product (numpy np.matmul at rank >= 3, torch.bmm /
  ;; torch.matmul): the LAST TWO axes are the matrix and every leading axis
  ;; broadcasts. A rank-1 operand is promoted for the product -- a row on the
  ;; left, a column on the right -- and its axis is dropped again from the
  ;; result, exactly like numpy. One outer x M x K x N walk over the flat
  ;; row-major index; the batch offsets advance through the %la-gather-strided
  ;; odometer. Width follows a, like every other transform.
  (let* ((da (array-dimensions a))
         (db (array-dimensions b))
         (avec (null (cdr da)))
         (bvec (null (cdr db)))
         (pa (if avec (cons 1 da) da))
         (pb (if bvec (append db (list 1)) db))
         (ra (length pa))
         (rb (length pb))
         (m (nth (- ra 2) pa))
         (k (nth (- ra 1) pa))
         (n (nth (- rb 1) pb))
         (ba (linalg::%la-drop-last pa 2))
         (bb (linalg::%la-drop-last pb 2))
         (bd (linalg::%la-batch-shape ba bb)))
    (unless (= k (nth (- rb 2) pb))
      (error "linalg: matmul inner dimensions differ"))
    (let* ((od
            (append bd (append (if avec nil (list m)) (if bvec nil (list n)))))
           (out (linalg::%la-make od 0.0 (linalg::%la-etype a)))
           (rsa (linalg::%la-batch-strides ba bd (* m k)))
           (rsb (linalg::%la-batch-strides bb bd (* k n)))
           (rbd (reverse bd))
           (idx (linalg::%la-zero-counters (length bd)))
           (batches (linalg::%la-from-size bd 0))
           (oa 0)
           (ob 0)
           (oo 0))
      (do ((z 0 (+ z 1)))
          ((>= z batches) out)
        (do ((i 0 (+ i 1)))
            ((>= i m))
          (do ((j 0 (+ j 1)))
              ((>= j n))
            (let ((acc 0))
              (do ((q 0 (+ q 1)))
                  ((>= q k))
                (setq acc
                      (+ acc
                         (* (row-major-aref a (+ oa (* i k) q))
                            (row-major-aref b (+ ob (* q n) j))))))
              (setf (row-major-aref out (+ oo (* i n) j)) acc))))
        (setq oo (+ oo (* m n)))
        (do ((pc idx (cdr pc))
             (pd rbd (cdr pd))
             (psa rsa (cdr psa))
             (psb rsb (cdr psb))
             (carry t))
            ((or (null pc) (not carry)))
          (rplaca pc (+ (car pc) 1))
          (setq oa (+ oa (car psa)))
          (setq ob (+ ob (car psb)))
          (if (< (car pc) (car pd))
              (setq carry nil)
              (progn
                (rplaca pc 0)
                (setq oa (- oa (* (car pd) (car psa))))
                (setq ob (- ob (* (car pd) (car psb)))))))))))

(defun linalg::%la-slice-bound (v n step startp)
  ;; One end of a slice spec normalized against an axis of extent n (numpy's
  ;; rule): nil takes the natural end for the step's direction, a negative
  ;; index counts from the end, and the result is clamped -- to [0, n] for a
  ;; positive step, to [-1, n-1] for a negative one.
  (let ((x
         (cond ((not (null v)) (if (< v 0) (+ v n) v))
               (startp (if (> step 0) 0 (- n 1)))
               (t (if (> step 0) n -1)))))
    (if (> step 0) (max 0 (min x n)) (max -1 (min x (- n 1))))))

(defun linalg::%la-tri (a k upper)
  ;; The shared triu/tril body: a copy of a with the elements outside the
  ;; k-th diagonal zeroed, applied to the LAST TWO axes of any rank >= 2
  ;; (numpy triu/tril broadcast over the leading axes the same way).
  (let ((d (array-dimensions a)))
    (unless (cdr d) (error "linalg: triu and tril expect rank >= 2"))
    (let* ((rank (length d))
           (rows (nth (- rank 2) d))
           (cols (nth (- rank 1) d))
           (outer (linalg::%la-head-size d (- rank 2)))
           (out (linalg::%la-like a)))
      (do ((o 0 (+ o 1)))
          ((>= o outer) out)
        (do ((i 0 (+ i 1)))
            ((>= i rows))
          (do ((j 0 (+ j 1)))
              ((>= j cols))
            (when (if upper (>= (- j i) k) (<= (- j i) k))
              (let ((f (+ (* o rows cols) (* i cols) j)))
                (setf (row-major-aref out f) (row-major-aref a f))))))))))

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

(defun linalg:expand-dims (a axis)
  ;; A copy of a with a new axis of extent 1 inserted at axis (numpy
  ;; np.expand_dims, torch's unsqueeze): a negative axis counts from the end of
  ;; the RESULT, so -1 appends. The row-major order is unchanged, so this is a
  ;; reshape; the width follows a.
  (let* ((d (array-dimensions a))
         (rank (length d))
         (ax (if (< axis 0) (+ axis rank 1) axis)))
    (unless (and (>= ax 0) (<= ax rank)) (error "linalg: axis out of range"))
    (linalg:reshape a (linalg::%la-insert-axis d ax 1))))

(defun linalg:squeeze (a &key axis)
  ;; A copy of a with extent-1 axes removed (numpy np.squeeze): with no :axis
  ;; every one of them, with an integer :axis (or a list of them, negative
  ;; counting from the end) only those -- and an axis whose extent is not 1
  ;; signals. Squeezing away EVERY axis returns the single element itself,
  ;; because linalg has no rank-0 arrays (a plain number is what ndim 0 means).
  (let* ((d (array-dimensions a))
         (rank (length d))
         (drop (linalg::%la-zero-counters rank)))
    (if (null axis)
        (do ((p d (cdr p)) (c drop (cdr c)))
            ((null p))
          (when (= (car p) 1) (rplaca c 1)))
        (do ((p (if (consp axis) axis (list axis)) (cdr p)))
            ((null p))
          (let ((ax (linalg::%la-norm-axis d (car p))))
            (unless (= (nth ax d) 1)
              (error "linalg: squeeze axis is not of extent 1"))
            (rplaca (nthcdr ax drop) 1))))
    (let ((od nil))
      (do ((p (reverse d) (cdr p)) (c (reverse drop) (cdr c)))
          ((null p))
        (when (= (car c) 0) (setq od (cons (car p) od))))
      (if (null od) (row-major-aref a 0) (linalg:reshape a od)))))

(defun linalg:concatenate (arrays &key (axis 0))
  ;; The arrays in the LIST arrays joined along an EXISTING axis (numpy
  ;; np.concatenate, torch.cat; default axis 0, negative counts from the end).
  ;; Every input needs the same rank and the same extents off that axis; the
  ;; joined axis's extent is their sum. Fresh array, width of the first input.
  (when (null arrays) (error "linalg: concatenate needs at least one array"))
  (let* ((d0 (array-dimensions (car arrays)))
         (rank (length d0))
         (ax (linalg::%la-norm-axis d0 axis))
         (inner (linalg::%la-tail-size d0 ax))
         (outer (linalg::%la-head-size d0 ax))
         (total 0))
    (do ((p arrays (cdr p)))
        ((null p))
      (let ((d (array-dimensions (car p))))
        (unless (= (length d) rank)
          (error "linalg: concatenate expects arrays of equal rank"))
        (do ((pd d (cdr pd)) (p0 d0 (cdr p0)) (k 0 (+ k 1)))
            ((null pd))
          (unless (or (= k ax) (= (car pd) (car p0)))
            (error "linalg: concatenate shapes differ off the axis")))
        (setq total (+ total (nth ax d)))))
    (let ((out
           (linalg::%la-make (linalg::%la-replace-axis d0 ax total) 0.0
                             (linalg::%la-etype (car arrays)))))
      (do ((o 0 (+ o 1)))
          ((>= o outer) out)
        (let ((dst (* o total inner)))
          (do ((p arrays (cdr p)))
              ((null p))
            (let* ((s (car p))
                   (blk (* (nth ax (array-dimensions s)) inner))
                   (src (* o blk)))
              (do ((k 0 (+ k 1)))
                  ((>= k blk))
                (setf (row-major-aref out (+ dst k))
                      (row-major-aref s (+ src k))))
              (setq dst (+ dst blk)))))))))

(defun linalg:stack (arrays &key (axis 0))
  ;; The arrays in the LIST arrays joined along a NEW axis (numpy np.stack):
  ;; every input must have the SAME shape, and the result has one more axis,
  ;; of extent (length arrays), at axis -- negative counting from the end of
  ;; the RESULT, so -1 appends it. Fresh array, width of the first input.
  (when (null arrays) (error "linalg: stack needs at least one array"))
  (let* ((d (array-dimensions (car arrays)))
         (rank (length d))
         (ax (if (< axis 0) (+ axis rank 1) axis)))
    (unless (and (>= ax 0) (<= ax rank)) (error "linalg: axis out of range"))
    (let* ((n (length arrays))
           (inner (linalg::%la-from-size d ax))
           (outer (linalg::%la-head-size d ax))
           (out
            (linalg::%la-make (linalg::%la-insert-axis d ax n) 0.0
                              (linalg::%la-etype (car arrays)))))
      (do ((p arrays (cdr p)) (i 0 (+ i 1)))
          ((null p) out)
        (let ((s (car p)))
          (unless (equal (array-dimensions s) d)
            (error "linalg: stack expects arrays of equal shape"))
          (do ((o 0 (+ o 1)))
              ((>= o outer))
            (let ((src (* o inner)) (dst (* (+ (* o n) i) inner)))
              (do ((q 0 (+ q 1)))
                  ((>= q inner))
                (setf (row-major-aref out (+ dst q))
                      (row-major-aref s (+ src q)))))))))))

(defun linalg:slice (a specs)
  ;; numpy BASIC slicing -- x[i0:j0:k0, i1:j1, ...] -- spelled as a LIST of one
  ;; spec per axis: nil leaves the axis whole, or (start end) / (start end step)
  ;; selects along it. A negative index counts from the end, nil in the start or
  ;; end position means "from the beginning" / "to the end", a negative step
  ;; walks the axis backwards, and a MISSING trailing spec leaves that axis
  ;; whole. Every axis is KEPT, extent 0 included (numpy's x[:, 0:3]); dropping
  ;; an axis is what linalg:row does. Fresh array of a's width.
  (let* ((d (array-dimensions a))
         (rank (length d))
         (sx (linalg::%la-strides d))
         (od nil)
         (os nil)
         (base 0))
    (when (> (length specs) rank)
      (error "linalg: slice expects at most one spec per axis"))
    (do ((pd d (cdr pd)) (ps specs (cdr ps)) (pt sx (cdr pt)))
        ((null pd))
      (let ((n (car pd)) (spec (if ps (car ps) nil)))
        (if (null spec)
            (progn
              (setq od (cons n od))
              (setq os (cons (car pt) os)))
            (let ((step (if (cddr spec) (caddr spec) 1)))
              (when (= step 0) (error "linalg: slice step must not be zero"))
              (let* ((s0 (linalg::%la-slice-bound (car spec) n step t))
                     (e0 (linalg::%la-slice-bound (cadr spec) n step nil)))
                (setq od (cons (max 0 (ceiling (/ (- e0 s0) step))) od))
                (setq os (cons (* step (car pt)) os))
                (setq base (+ base (* s0 (car pt)))))))))
    (linalg::%la-gather-strided a (reverse od) os base
                                (eq (linalg::%la-etype a) 'single-float))))

(defun linalg:triu (a &key (k 0))
  ;; The upper triangle of a: a copy with everything BELOW the k-th diagonal
  ;; zeroed (numpy np.triu; k = 0 keeps the main diagonal, a positive k moves
  ;; the boundary up and to the right). Rank >= 2; for a stack of matrices the
  ;; last two axes are the matrix. This is the causal / subsequent attention
  ;; mask when applied to an all-ones matrix.
  (linalg::%la-tri a k t))

(defun linalg:tril (a &key (k 0))
  ;; The lower triangle of a: a copy with everything ABOVE the k-th diagonal
  ;; zeroed (numpy np.tril); the linalg:triu rules with the comparison flipped.
  (linalg::%la-tri a k nil))

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

(defun linalg:power (a b)
  ;; Elementwise a raised to b (numpy np.power / the ** operator); either
  ;; operand may be a scalar and two arrays broadcast, like linalg:mul. Both
  ;; operands go through the same float element model as the rest of linalg,
  ;; so a fractional exponent is the ordinary float power.
  (linalg::%la-bcast (function expt) a b))

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

;; --- the error function -------------------------------------------------------
;; Not in numpy proper (it is scipy.special.erf), and here for the same reason
;; softmax is: it is the array-level primitive an activation needs -- the EXACT
;; Gaussian error linear unit is x * (1 + erf(x / sqrt(2))) / 2, which is
;; nn.GELU's default -- and one implementation keeps every backend identical.

(defun linalg::%la-erf-1 (x)
  ;; erf of one number, by the all-positive-term series (A&S 7.1.6)
  ;;
  ;;   erf(x) = 2x / sqrt(pi) * e^(-x^2) * sum_{n>=0} (2 x^2)^n / (1.3.5...(2n+1))
  ;;
  ;; rather than the alternating Maclaurin series, whose cancellation loses every
  ;; significant digit by |x| ~ 3. Every term is positive, so the sum is accurate
  ;; to the last ulp wherever it is used, and the ratio 2x^2 / (2n + 3) drives it
  ;; down once n passes x^2. Beyond |x| = 6 the result is +-1 to within a double's
  ;; resolution (1 - erf(6) is about 2e-17), which also bounds the loop.
  (let ((ax (abs x)))
    (if (>= ax 6.0)
        (if (< x 0.0) -1.0 1.0)
        (let ((term 1.0) (total 1.0) (xx (* 2.0 ax ax)))
          (do ((n 1 (+ n 1)))
              ((> n 200))
            (setq term (/ (* term xx) (+ (* 2.0 n) 1.0)))
            (setq total (+ total term))
            (when (< term (* 1.0e-17 total)) (return)))
          (let ((v (* 1.1283791670955126 ax (exp (- (* ax ax))) total)))
            (if (< x 0.0) (- v) v))))))

(defun linalg:erf (a)
  ;; Elementwise Gauss error function (scipy.special.erf):
  ;; erf(x) = 2 / sqrt(pi) * integral from 0 to x of e^(-t^2) dt, an odd function
  ;; rising from -1 to 1. Accurate to a double's last ulps over the whole range
  ;; (see linalg::%la-erf-1); the complementary erfc is (- 1.0 (linalg:erf a)),
  ;; which loses precision in the far tail and is therefore not a member of its
  ;; own.
  (linalg:emap (function linalg::%la-erf-1) a))

;; --- activations (softmax) ------------------------------------------------------
;; softmax is not in numpy proper (it is scipy.special.softmax / torch.softmax),
;; but it lives here for the same reason relu does: it is the array-level
;; primitive an activation layer needs, and one implementation keeps every
;; backend identical. Both are the max-subtracted (numerically stable) forms.

(defun linalg:softmax (a &key axis)
  ;; The softmax of a: exp(a - max(a)) normalized to sum to 1. With no :axis
  ;; the whole array is one distribution (scipy's default); with an integer
  ;; :axis (negative counts from the end) every slice along that axis is
  ;; normalized on its own, which is the attention-weight form
  ;; (torch.softmax(x, dim)). The maximum is subtracted first, so a large
  ;; logit cannot overflow.
  (if (null axis)
      (let ((e (linalg:exp (linalg:sub a (linalg:amax a)))))
        (linalg:div e (linalg:sum e)))
      (let* ((ax (linalg::%la-norm-axis (array-dimensions a) axis))
             (e
              (linalg:exp (linalg:sub a (linalg:amax a :axis ax :keepdims t)))))
        (linalg:div e (linalg:sum e :axis ax :keepdims t)))))

(defun linalg:log-softmax (a &key axis)
  ;; The logarithm of linalg:softmax, computed as (a - max) - log(sum(exp(a -
  ;; max))) rather than as (log (softmax a)) so an exactly-zero weight gives
  ;; -infinity instead of a NaN. The :axis rules are linalg:softmax's. This is
  ;; the numerically stable half of a cross-entropy loss.
  (if (null axis)
      (let ((s (linalg:sub a (linalg:amax a))))
        (linalg:sub s (log (linalg:sum (linalg:exp s)))))
      (let* ((ax (linalg::%la-norm-axis (array-dimensions a) axis))
             (s (linalg:sub a (linalg:amax a :axis ax :keepdims t))))
        (linalg:sub s
         (linalg:log (linalg:sum (linalg:exp s) :axis ax :keepdims t))))))

;; --- products ----------------------------------------------------------------

(defun linalg:dot (a b)
  ;; numpy-style dot: vector . vector -> scalar, matrix . vector -> vector,
  ;; vector . matrix -> vector, matrix . matrix -> matrix; a scalar operand
  ;; multiplies elementwise. Rank >= 2 only on both sides -- numpy's np.dot
  ;; contracts a rank-n operand against the SECOND-TO-LAST axis of the other,
  ;; which is not what a stacked matrix product means, so that shape is an
  ;; error pointing at linalg:matmul rather than a silently wrong answer.
  (cond ((or (numberp a) (numberp b)) (linalg:mul a b))
        (t
         (let ((ra (cdr (array-dimensions a))) (rb (cdr (array-dimensions b))))
           (when (or (cdr ra) (cdr rb))
             (error
              "linalg: dot expects rank <= 2 (linalg:matmul stacks rank >= 3)"))
           (cond ((and (null ra) (null rb)) (linalg::%la-dot-vv a b))
                 ((and ra (null rb)) (linalg::%la-dot-mv a b))
                 ((and (null ra) rb) (linalg::%la-dot-vm a b))
                 (t (linalg::%la-matmul a b)))))))

(defun linalg:matmul (a b)
  ;; The matrix product (numpy np.matmul, the @ operator). At rank <= 2 it is
  ;; the plain product (matrix . vector included), the linalg:dot path. At rank
  ;; >= 3 on either side it is the STACKED product (torch.bmm / torch.matmul):
  ;; the last two axes are the matrix and every leading axis broadcasts, so a
  ;; (b h n d) query times a (b h d n) key gives (b h n n) attention scores.
  ;; Scalar operands are rejected at every rank.
  (when (or (numberp a) (numberp b)) (error "linalg: matmul expects arrays"))
  (if (or (> (length (array-dimensions a)) 2)
          (> (length (array-dimensions b)) 2))
      (linalg::%la-matmul-nd a b)
      (linalg:dot a b)))

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

(defun linalg:var (a &key axis keepdims (ddof 0))
  ;; The variance -- the mean of the squared deviations from the mean -- of
  ;; every element (no :axis) or along an axis (the :axis/:keepdims rules of
  ;; linalg:sum). The divisor is (n - :ddof): the default ddof 0 is numpy's
  ;; np.var and torch's unbiased=False, ddof 1 is the sample variance
  ;; (Bessel's correction).
  (if (null axis)
      (let ((d (linalg:sub a (linalg:mean a))) (n (linalg:size a)))
        (when (<= (- n ddof) 0)
          (error "linalg: var needs more elements than ddof"))
        (linalg::%la-wrap-scalar a (/ (linalg:sum (linalg:mul d d)) (- n ddof))
                                 keepdims))
      (let* ((ax (linalg::%la-norm-axis (array-dimensions a) axis))
             (n (nth ax (array-dimensions a)))
             (d (linalg:sub a (linalg:mean a :axis ax :keepdims t))))
        (when (<= (- n ddof) 0)
          (error "linalg: var needs more elements than ddof"))
        (linalg:div (linalg:sum (linalg:mul d d) :axis ax :keepdims keepdims)
                    (- n ddof)))))

(defun linalg:std (a &key axis keepdims (ddof 0))
  ;; The standard deviation: the square root of linalg:var, with the same
  ;; :axis / :keepdims / :ddof rules (numpy np.std). This is the LayerNorm
  ;; denominator.
  (let ((v (linalg:var a :axis axis :keepdims keepdims :ddof ddof)))
    (if (numberp v) (sqrt v) (linalg:sqrt v))))

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

(defun linalg:where (mask x y)
  ;; Elementwise selection (numpy np.where): the element of x where mask is
  ;; NON-ZERO, the element of y where it is zero -- so the 0.0/1.0 masks
  ;; linalg:greater and friends return select directly, and no multiply-by-mask
  ;; detour is needed (that detour turns an infinite operand into a NaN; this
  ;; does not, which is what makes a -infinity attention mask work). All three
  ;; operands may be scalars or arrays and broadcast together by the numpy
  ;; rules; the result keeps x's width when x is an array, else y's.
  (let ((od nil))
    (unless (numberp mask) (setq od (array-dimensions mask)))
    (unless (numberp x)
      (setq od
            (if od
                (linalg::%la-bcast-shape od (array-dimensions x))
                (array-dimensions x))))
    (unless (numberp y)
      (setq od
            (if od
                (linalg::%la-bcast-shape od (array-dimensions y))
                (array-dimensions y))))
    (if (null od)
        (if (= mask 0) y x)
        (let* ((mm (if (numberp mask) nil (linalg::%la-broadcast-to mask od)))
               (xx (if (numberp x) nil (linalg::%la-broadcast-to x od)))
               (yy (if (numberp y) nil (linalg::%la-broadcast-to y od)))
               (out
                (linalg::%la-make od 0.0
                                  (if xx
                                      (linalg::%la-etype xx)
                                      (if yy
                                          (linalg::%la-etype yy)
                                          'double-float))))
               (n (array-total-size out)))
          (do ((k 0 (+ k 1)))
              ((>= k n) out)
            (setf (row-major-aref out k)
                  (if (= (if mm (row-major-aref mm k) mask) 0)
                      (if yy (row-major-aref yy k) y)
                      (if xx (row-major-aref xx k) x))))))))

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

(defun linalg::%la-scatter-rows (z g idx)
  ;; The adjoint of take-rows, in place: slab i of g is ADDED into slab idx[i]
  ;; of z (any rank >= 1, index values truncated to integers, a repeated index
  ;; accumulating every contribution). Returns z. An internal member of the
  ;; same kind as %la-im2col: the loop torch:index-select's backward used to
  ;; spell inline over row-major-aref, moved here so the --simd seam -- which
  ;; intercepts linalg: members and nothing else -- can reach it.
  (let* ((slab (linalg::%la-tail-size (array-dimensions z) 0)) (m (length idx)))
    (do ((i 0 (+ i 1)))
        ((>= i m) z)
      (let ((dst (* (truncate (aref idx i)) slab)) (src (* i slab)))
        (do ((k 0 (+ k 1)))
            ((>= k slab))
          (setf (row-major-aref z (+ dst k))
           (+ (row-major-aref z (+ dst k)) (row-major-aref g (+ src k)))))))))

(defun linalg::%la-sum-squares (g acc)
  ;; acc plus the sum of the squares of g's elements, accumulated exactly as
  ;; torch:clip-grad-norm's total was: a left fold in double from acc, one
  ;; element at a time, each read widened. An internal member of the same kind
  ;; as %la-adam-step -- a loop a library ABOVE this one ran per parameter per
  ;; step, moved here so the --simd seam can reach it. Returns the number.
  (let ((n (array-total-size g)) (total acc))
    (do ((k 0 (+ k 1)))
        ((>= k n) total)
      (let ((v (row-major-aref g k))) (setq total (+ total (* v v)))))))

(defun linalg::%la-scale (g s)
  ;; g scaled IN PLACE by the number s, element by element -- the other half of
  ;; torch:clip-grad-norm, which rewrites the gradients the optimizer is about
  ;; to read. Returns g.
  (let ((n (array-total-size g)))
    (do ((k 0 (+ k 1)))
        ((>= k n) g)
      (setf (row-major-aref g k) (* (row-major-aref g k) s)))))

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

(defun linalg::%la-rng-state ()
  ;; The generator's three state words as a packed double vector. It is what
  ;; makes %la-rng-fill below a PURE function of its arguments -- the state
  ;; goes in as an array and the state it ends on comes back as one -- which
  ;; is what a kernel on this seam can be.
  (let ((s (linalg::%la-make 3 0.0 nil)))
    (setf (aref s 0) linalg::%la-rng-s1)
    (setf (aref s 1) linalg::%la-rng-s2)
    (setf (aref s 2) linalg::%la-rng-s3)
    s))

(defun linalg::%la-rng-restore (s)
  ;; Puts a state vector back into the three specials, so the next scalar draw
  ;; continues exactly where the fill that produced it left off. Returns s.
  (setq linalg::%la-rng-s1 (floor (aref s 0)))
  (setq linalg::%la-rng-s2 (floor (aref s 1)))
  (setq linalg::%la-rng-s3 (floor (aref s 2)))
  s)

(defun linalg::%la-rng-fill (out st mode lo span)
  ;; Fills every element of out from the state st, and answers the state the
  ;; generator ends on. mode picks the element rule, each spelled exactly as
  ;; its caller used to spell it: 0 is one uniform [0, 1) draw (linalg:rand),
  ;; 1 the sum of twelve draws minus 6 (linalg:randn's Irwin-Hall normal),
  ;; and 2 is lo + span * draw (linalg:uniform).
  ;;
  ;; The three fills were the RNG half of todo-473's profile: a boxed do loop
  ;; per element, with a boxed double per draw. Collapsing them into ONE
  ;; internal member is what puts them on the --simd seam, which intercepts
  ;; linalg: members and nothing else. The generator's rule itself does not
  ;; move -- this loop still calls %la-rng-next, so there is still exactly one
  ;; copy of it -- and the specials are its scratch: the caller restores them
  ;; from the RETURNED vector, which is also what a kernel has to write.
  (linalg::%la-rng-restore st)
  (let ((n (array-total-size out)))
    (do ((k 0 (+ k 1)))
        ((>= k n))
      (if (= mode 1)
          (let ((acc 0.0))
            (do ((j 0 (+ j 1)))
                ((>= j 12))
              (setq acc (+ acc (linalg::%la-rng-next))))
            (setf (row-major-aref out k) (- acc 6.0)))
          (if (= mode 0)
              (setf (row-major-aref out k) (linalg::%la-rng-next))
              (setf (row-major-aref out k)
                    (+ lo (* span (linalg::%la-rng-next))))))))
  (linalg::%la-rng-state))

(defun linalg:rand (shape &key element-type)
  ;; An array of uniform [0, 1) draws (np.random.rand, but with a shape
  ;; designator like linalg:zeros; double by default, :element-type
  ;; 'single-float for #f).
  (let ((out (linalg::%la-make shape 0.0 element-type)))
    (linalg::%la-rng-restore
     (linalg::%la-rng-fill out (linalg::%la-rng-state) 0 0.0 1.0))
    out))

(defun linalg:randn (shape &key element-type)
  ;; An array of standard-normal draws via Irwin-Hall (np.random.randn, but
  ;; with a shape designator; see the section comment for the distribution
  ;; caveat).
  (let ((out (linalg::%la-make shape 0.0 element-type)))
    (linalg::%la-rng-restore
     (linalg::%la-rng-fill out (linalg::%la-rng-state) 1 0.0 1.0))
    out))

(defun linalg:uniform (lo hi shape &key element-type)
  ;; An array of uniform draws in [lo, hi) (np.random.uniform, but with a
  ;; required shape designator).
  (let ((out (linalg::%la-make shape 0.0 element-type)))
    (linalg::%la-rng-restore
     (linalg::%la-rng-fill out (linalg::%la-rng-state) 2 lo (- hi lo)))
    out))

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

;; --- the fused optimizer update (torch:adam / torch:adamw) --------------------
;; An internal member of the same kind as %la-im2col: a fused element-wise loop
;; that a library ABOVE this one calls once per parameter per step, put here
;; because the --simd seam intercepts linalg: members and nothing else
;; (todo-473). torch::%o-adam-step holds the rule's documentation and is its
;; only caller; nothing in the numpy surface reaches this.

(defun linalg::%la-adam-step (x g m v ps)
  ;; Adam's fused element-wise update, IN PLACE over four aligned arrays -- the
  ;; parameter x, its gradient g, and the two moment buffers m and v -- with
  ;; every hyper-parameter of the rule packed into the double vector ps:
  ;;
  ;;   0 lr   1 lr*wd   2 wd   3 b1   4 1-b1   5 b2   6 1-b2   7 eps
  ;;   8 c1   9 c2   10 mode
  ;;
  ;; where c1 / c2 are the two bias corrections and mode selects the weight
  ;; decay: 0 none, 1 COUPLED (torch.optim.Adam's L2 term rides the gradient),
  ;; 2 DECOUPLED (torch.optim.AdamW shrinks the parameter itself). lr*wd is
  ;; passed already multiplied so that an exact-rational lr and wd -- which the
  ;; caller may hold and this vector cannot -- still meet in the product the
  ;; scalar rule formed, (* lr wd x).
  ;;
  ;; x may be a plain NUMBER (a scalar parameter, whose m and v are one-element
  ;; buffers), and so may g. The answer is the parameter's new data: x itself
  ;; when it is an array, since the update is in place.
  (let* ((lr (aref ps 0))
         (lrwd (aref ps 1))
         (wd (aref ps 2))
         (b1 (aref ps 3))
         (omb1 (aref ps 4))
         (b2 (aref ps 5))
         (omb2 (aref ps 6))
         (eps (aref ps 7))
         (c1 (aref ps 8))
         (c2 (aref ps 9))
         (mode (aref ps 10))
         (sx (numberp x))
         (sg (numberp g))
         (n (if sx 1 (array-total-size x)))
         (out x))
    (do ((k 0 (+ k 1)))
        ((>= k n) out)
      (let* ((x0 (if sx x (row-major-aref x k)))
             (xv (if (= mode 2) (- x0 (* lrwd x0)) x0))
             (g0 (if sg g (row-major-aref g k)))
             (gv (if (= mode 1) (+ g0 (* wd x0)) g0))
             (mk (+ (* b1 (row-major-aref m k)) (* omb1 gv)))
             (vk (+ (* b2 (row-major-aref v k)) (* omb2 gv gv))))
        (setf (row-major-aref m k) mk)
        (setf (row-major-aref v k) vk)
        (let ((nv (- xv (/ (* lr (/ mk c1)) (+ (sqrt (/ vk c2)) eps)))))
          (if sx (setq out nv) (setf (row-major-aref x k) nv)))))))
