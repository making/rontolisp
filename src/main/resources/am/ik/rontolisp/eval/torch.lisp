;; The torch package: a PyTorch-style tensor with reverse-mode automatic
;; differentiation, written in rontolisp itself over the linalg package so a
;; single implementation runs on every backend: the interpreter loads these
;; definitions lazily on first use of a torch: function, and the compile path
;; splices them into the program when it references the package (see
;; TorchLibrary.java). The linalg library is pulled in by the same mechanisms
;; (the splice chain runs TorchLibrary.process before LinalgLibrary.process, so
;; the linalg references written here are detected too).
;;
;; Portability constraints honored here (like linalg.lisp, see .kb/linalg.md):
;; do loops always declare at least one variable, parameters are never assigned
;; with setq (let-rebound instead), and every array kernel call spells its
;; keywords literally so the --simd interceptors can pattern-match the call
;; sites (a torch program is accelerated for free wherever linalg is).
;;
;; DESIGN (recorded in .kb/torch.md): the package is defun-only BY DESIGN --
;; no defclass/defmethod/defstruct, ever -- so LibraryDefunPruner's existing
;; defun/defparameter pruning covers every definition here and a program that
;; only calls torch:softmax does not carry the whole autograd surface. The
;; tensor is a fixed-layout six-slot general vector:
;;
;;   slot 0  the tag symbol torch::%tensor (torch:tensorp discriminates on it)
;;   slot 1  data          a linalg array (packed float, any rank) or a number
;;                         (a plain number is the rank-0 scalar tensor)
;;   slot 2  grad          nil, or a value of data's shape (accumulated by
;;                         torch:backward; a raw linalg value, not a tensor)
;;   slot 3  requires-grad the LEAF flag set by (torch:tensor x :requires-grad t)
;;   slot 4  parents       the input tensors this one was computed from
;;   slot 5  backward-fn   nil, or (lambda (grad-out) ...) -> the list of
;;                         per-parent gradients (nil for an untracked parent)
;;
;; A tensor "tracks" gradients when slot 3 or slot 5 is set. An operation
;; records slots 4/5 only while torch::*grad-enabled* is true (the torch:no-grad
;; macro rebinds it) AND some operand tracks; otherwise the result is a plain
;; constant leaf and the inputs stay collectable.

(defparameter torch::*grad-enabled* t)

;; --- the tensor record -------------------------------------------------------

(defun torch::%t-new (data grad requires-grad parents backward-fn)
  ;; The one constructor of the six-slot record described in the header.
  (let ((tn (make-array 6 :initial-element nil)))
    (setf (aref tn 0) 'torch::%tensor)
    (setf (aref tn 1) data)
    (setf (aref tn 2) grad)
    (setf (aref tn 3) requires-grad)
    (setf (aref tn 4) parents)
    (setf (aref tn 5) backward-fn)
    tn))

(defun torch:tensorp (x)
  ;; Whether x is a torch tensor: the fixed-layout six-slot general vector
  ;; whose first slot is the tag symbol.
  (if (and (arrayp x) (not (stringp x)) (equal (array-dimensions x) '(6))
           (eq (row-major-aref x 0) 'torch::%tensor))
      t
      nil))

(defun torch::%t-check (x)
  ;; Signals unless x is a tensor; returns it.
  (unless (torch:tensorp x) (error "torch: expected a tensor"))
  x)

(defun torch::%t-as-data (x element-type)
  ;; x coerced to tensor data: a number becomes the double scalar, a list goes
  ;; through linalg:from-list (a flat list or a list of equal-length rows), and
  ;; an array becomes a fresh packed copy -- preserving its width by default,
  ;; converted when :element-type is given.
  (cond ((numberp x) (* x 1.0))
        ((consp x) (linalg:from-list x :element-type element-type))
        ((and (arrayp x) (not (stringp x)))
         (if element-type
             (linalg:add
              (linalg:full (array-dimensions x) 0.0 :element-type element-type)
              x)
             (linalg:add x 0.0)))
        (t (error "torch: tensor expects a number, a list or an array"))))

(defun torch:tensor (x &key requires-grad element-type)
  ;; A fresh LEAF tensor from a number, a list, an array, a linalg array or
  ;; another tensor (whose data is copied). :requires-grad t marks it as a
  ;; parameter whose gradient torch:backward should fill in; :element-type
  ;; 'single-float builds packed single-float (#f) data.
  (let ((src (if (torch:tensorp x) (aref x 1) x)))
    (torch::%t-new (torch::%t-as-data src element-type) nil
                   (if requires-grad t nil) nil nil)))

(defun torch:data (tn)
  ;; The tensor's data: a linalg array, or a number for a scalar tensor.
  (torch::%t-check tn)
  (aref tn 1))

(defun torch:grad (tn)
  ;; The accumulated gradient (data's shape), or nil before torch:backward has
  ;; reached this tensor. A raw linalg value, not a tensor.
  (torch::%t-check tn)
  (aref tn 2))

(defun torch:shape (tn)
  ;; The dims list of the tensor's data; nil for a scalar tensor (rank 0).
  (let ((d (torch:data tn))) (if (numberp d) nil (array-dimensions d))))

(defun torch:item (tn)
  ;; The single element of a scalar (or one-element) tensor, as a number.
  (let ((d (torch:data tn)))
    (cond ((numberp d) d)
          ((= (array-total-size d) 1) (row-major-aref d 0))
          (t (error "torch: item expects a one-element tensor")))))

(defun torch:detach (tn)
  ;; A new leaf tensor SHARING tn's data, cut off from the tape: no
  ;; requires-grad, no parents, no backward-fn.
  (torch::%t-check tn)
  (torch::%t-new (aref tn 1) nil nil nil nil))

(defun torch:zero-grad (tn)
  ;; Clears the accumulated gradient (sets it back to nil); returns the tensor.
  (torch::%t-check tn)
  (setf (aref tn 2) nil)
  tn)

(defun torch:requires-grad-p (tn)
  ;; Whether the tensor participates in autograd: a leaf marked
  ;; :requires-grad, or any result recorded on the tape.
  (torch::%t-check tn)
  (if (or (aref tn 3) (aref tn 5)) t nil))

(defun torch::%t-track-p (tn)
  ;; The internal (unchecked) spelling of torch:requires-grad-p.
  (or (aref tn 3) (aref tn 5)))

(defun torch::%t-wrap (x)
  ;; The operand rule of every operation: a tensor passes through; a number, a
  ;; raw array or a list becomes a constant leaf (an array is used as data
  ;; without copying).
  (cond ((torch:tensorp x) x)
        ((numberp x) (torch::%t-new x nil nil nil nil))
        ((consp x) (torch:tensor x))
        ((and (arrayp x) (not (stringp x))) (torch::%t-new x nil nil nil nil))
        (t (error "torch: expected a tensor, a number, a list or an array"))))

(defun torch::%t-wrap-all (tensors)
  ;; torch::%t-wrap over a list of operands (torch:cat / torch:stack).
  (mapcar (function torch::%t-wrap) tensors))

(defun torch::%t-any-tracked (parents)
  ;; Whether any parent tracks gradients.
  (do ((p parents (cdr p)))
      ((null p) nil)
    (when (torch::%t-track-p (car p)) (return t))))

(defun torch::%t-result (data parents backward-fn)
  ;; The result rule of every operation: the tape edge (parents + backward-fn)
  ;; is recorded only while torch::*grad-enabled* is true and some parent
  ;; tracks gradients; otherwise the result is a plain constant leaf.
  (if (and torch::*grad-enabled* (torch::%t-any-tracked parents))
      (torch::%t-new data nil nil parents backward-fn)
      (torch::%t-new data nil nil nil nil)))

;; --- scalar-safe raw kernels -------------------------------------------------
;; The binary linalg kernels (add/sub/mul/div/power/equal/greater/where)
;; already accept plain numbers on either side, so tensor data that reduced to
;; a scalar flows straight through them. The unary ufuncs are emap-based and
;; array-only, so each gets a number branch here; the linalg spelling stays a
;; literal call for the --simd interceptors.

(defun torch::%t-rneg (x) (if (numberp x) (- x) (linalg:negative x)))

(defun torch::%t-rexp (x) (if (numberp x) (exp x) (linalg:exp x)))

(defun torch::%t-rlog (x) (if (numberp x) (log x) (linalg:log x)))

(defun torch::%t-rsqrt (x) (if (numberp x) (sqrt x) (linalg:sqrt x)))

(defun torch::%t-rtanh (x) (if (numberp x) (tanh x) (linalg:tanh x)))

;; --- the broadcasting adjoints -----------------------------------------------

(defun torch::%t-unbroadcast (g x)
  ;; The broadcasting adjoint every elementwise backward routes through: the
  ;; incoming gradient g reduced to the shape of the operand x it belongs to,
  ;; SUMMED over every broadcast axis -- each leading axis x does not have, and
  ;; each axis where x's extent is 1 and g's is larger -- so a (d) bias added
  ;; to a (b s d) activation gets the (d) gradient it should.
  (cond ((numberp x) (if (numberp g) g (linalg:sum g)))
        ((numberp g) (linalg:add (linalg:zeros-like x) g))
        (t (let ((dx (array-dimensions x)) (out g))
             (do ((extra
                   (- (length (array-dimensions g)) (length dx))
                   (- extra 1)))
                 ((<= extra 0))
               (setq out (linalg:sum out :axis 0)))
             (do ((pd dx (cdr pd)) (ax 0 (+ ax 1)))
                 ((null pd) out)
               (when (and (= (car pd) 1) (> (nth ax (array-dimensions out)) 1))
                 (setq out (linalg:sum out :axis ax :keepdims t))))))))

(defun torch::%t-keepdims (v x ax)
  ;; A reduction result v of x along ax normalized to the keepdims SHAPE (the
  ;; reduced axis kept with extent 1) so it broadcasts against x: a scalar, a
  ;; whole-array reduction and an already-keepdims array pass through.
  (if (or (null ax) (numberp v)
          (= (length (array-dimensions v)) (length (array-dimensions x))))
      v
      (linalg:expand-dims v ax)))

(defun torch::%t-grad-bcast (g x ax)
  ;; The reduction adjoint: the reduced gradient g (a scalar, a keepdims array
  ;; or the axis-dropped array of a reduction of x along ax) materialized back
  ;; at x's shape.
  (let ((gk (torch::%t-keepdims g x ax)))
    (linalg:add (linalg:zeros-like x) gk)))

(defun torch::%t-grad-reshape (g x)
  ;; The adjoint of any pure rearrangement (reshape/view/unsqueeze/squeeze):
  ;; g laid back out at x's shape -- row-major order is shared, so it is a
  ;; reshape; the scalar a squeezed-away all-extent-1 array produced
  ;; broadcasts back into that one cell.
  (if (numberp g)
      (linalg:add (linalg:zeros-like x) g)
      (linalg:reshape g (array-dimensions x))))

;; --- reverse-mode autograd ---------------------------------------------------

(defun torch::%t-topo (node state)
  ;; Depth-first walk over the tape from node: state is a (visited . order)
  ;; pair mutated in place, and order ends up in REVERSE TOPOLOGICAL order --
  ;; every tensor after all of its consumers (reverse DFS finish order) -- so
  ;; torch:backward can process it front to back. Visited membership is
  ;; identity (member's eql on the record vectors), so a diamond -- a residual
  ;; connection x + f(x) -- is visited once.
  (unless (member node (car state))
    (rplaca state (cons node (car state)))
    (do ((p (aref node 4) (cdr p)))
        ((null p))
      (torch::%t-topo (car p) state))
    (rplacd state (cons node (cdr state))))
  state)

(defun torch::%t-accum (tn g)
  ;; += accumulation into the grad slot: a tensor reached by more than one
  ;; path (a residual connection, a reused embedding row) collects the SUM of
  ;; its path gradients, never the last one.
  (let ((old (aref tn 2)))
    (setf (aref tn 2) (if (null old) g (linalg:add old g)))))

(defun torch:backward (tn)
  ;; Reverse-mode gradient of a SCALAR (one-element) tensor: seeds its grad
  ;; with 1.0, walks the tape in reverse topological order, and accumulates
  ;; each node's per-parent gradients into the parents' grad slots. Gradients
  ;; are retained on intermediate tensors too (read them with torch:grad);
  ;; returns nil.
  (torch::%t-check tn)
  (let ((d (aref tn 1)))
    (unless (or (numberp d) (= (array-total-size d) 1))
      (error "torch: backward expects a scalar (one-element) tensor"))
    (torch::%t-accum tn
     (if (numberp d) 1.0 (linalg:add (linalg:zeros-like d) 1.0)))
    (let ((order (cdr (torch::%t-topo tn (cons nil nil)))))
      (do ((p order (cdr p)))
          ((null p))
        (let* ((node (car p)) (bf (aref node 5)) (g (aref node 2)))
          (when (and bf g)
            (let ((gs (funcall bf g)))
              (do ((pp (aref node 4) (cdr pp)) (gg gs (cdr gg)))
                  ((null pp))
                (let ((par (car pp)) (pg (car gg)))
                  (when (and pg (torch::%t-track-p par))
                    (torch::%t-accum par pg)))))))))
    nil))

;; --- elementwise arithmetic --------------------------------------------------

(defun torch:add (a b)
  ;; Differentiable elementwise a + b with numpy broadcasting (linalg:add);
  ;; either operand may be a tensor, a number, an array or a list.
  (let* ((ta (torch::%t-wrap a))
         (tb (torch::%t-wrap b))
         (xa (aref ta 1))
         (xb (aref tb 1)))
    (torch::%t-result (linalg:add xa xb) (list ta tb)
                      (lambda (g)
                        (list (when (torch::%t-track-p ta)
                                (torch::%t-unbroadcast g xa))
                              (when (torch::%t-track-p tb)
                                (torch::%t-unbroadcast g xb)))))))

(defun torch:sub (a b)
  ;; Differentiable elementwise a - b with numpy broadcasting (linalg:sub).
  (let* ((ta (torch::%t-wrap a))
         (tb (torch::%t-wrap b))
         (xa (aref ta 1))
         (xb (aref tb 1)))
    (torch::%t-result (linalg:sub xa xb) (list ta tb)
                      (lambda (g)
                        (list (when (torch::%t-track-p ta)
                                (torch::%t-unbroadcast g xa))
                              (when (torch::%t-track-p tb)
                                (torch::%t-unbroadcast (torch::%t-rneg g)
                                                       xb)))))))

(defun torch:mul (a b)
  ;; Differentiable elementwise (Hadamard) a * b with numpy broadcasting
  ;; (linalg:mul; the matrix product is torch:matmul).
  (let* ((ta (torch::%t-wrap a))
         (tb (torch::%t-wrap b))
         (xa (aref ta 1))
         (xb (aref tb 1)))
    (torch::%t-result (linalg:mul xa xb) (list ta tb)
                      (lambda (g)
                        (list (when (torch::%t-track-p ta)
                                (torch::%t-unbroadcast (linalg:mul g xb) xa))
                              (when (torch::%t-track-p tb)
                                (torch::%t-unbroadcast (linalg:mul g xa)
                                                       xb)))))))

(defun torch:div (a b)
  ;; Differentiable elementwise a / b with numpy broadcasting (linalg:div).
  (let* ((ta (torch::%t-wrap a))
         (tb (torch::%t-wrap b))
         (xa (aref ta 1))
         (xb (aref tb 1)))
    (torch::%t-result (linalg:div xa xb) (list ta tb)
                      (lambda (g)
                        (list (when (torch::%t-track-p ta)
                                (torch::%t-unbroadcast (linalg:div g xb) xa))
                              (when (torch::%t-track-p tb)
                                (torch::%t-unbroadcast (torch::%t-rneg
                                                        (linalg:div
                                                         (linalg:mul g xa)
                                                         (linalg:mul xb xb)))
                                                       xb)))))))

(defun torch:neg (a)
  ;; Differentiable elementwise negation.
  (let ((ta (torch::%t-wrap a)))
    (torch::%t-result (torch::%t-rneg (aref ta 1)) (list ta)
                      (lambda (g) (list (torch::%t-rneg g))))))

(defun torch:power (a b)
  ;; Differentiable elementwise a ** b (linalg:power); either operand may be a
  ;; scalar and both are differentiable. The exponent's gradient needs
  ;; (log a), so it is only computed when b tracks gradients -- and is only
  ;; meaningful for a positive base.
  (let* ((ta (torch::%t-wrap a))
         (tb (torch::%t-wrap b))
         (xa (aref ta 1))
         (xb (aref tb 1))
         (out (linalg:power xa xb)))
    (torch::%t-result out (list ta tb)
                      (lambda (g)
                        (list (when (torch::%t-track-p ta)
                                (torch::%t-unbroadcast (linalg:mul g
                                                        (linalg:mul xb
                                                         (linalg:power xa
                                                          (linalg:sub xb 1))))
                                                       xa))
                              (when (torch::%t-track-p tb)
                                (torch::%t-unbroadcast (linalg:mul g
                                                        (linalg:mul out
                                                         (torch::%t-rlog xa)))
                                                       xb)))))))

;; --- elementwise transcendentals and activations -----------------------------

(defun torch:exp (a)
  ;; Differentiable elementwise e^x; the adjoint reuses the forward result
  ;; (d/dx e^x = e^x).
  (let* ((ta (torch::%t-wrap a)) (out (torch::%t-rexp (aref ta 1))))
    (torch::%t-result out (list ta) (lambda (g) (list (linalg:mul g out))))))

(defun torch:log (a)
  ;; Differentiable elementwise natural log (d/dx ln x = 1/x).
  (let* ((ta (torch::%t-wrap a)) (xa (aref ta 1)))
    (torch::%t-result (torch::%t-rlog xa) (list ta)
                      (lambda (g) (list (linalg:div g xa))))))

(defun torch:sqrt (a)
  ;; Differentiable elementwise square root (d/dx sqrt x = 1 / (2 sqrt x)).
  (let* ((ta (torch::%t-wrap a)) (out (torch::%t-rsqrt (aref ta 1))))
    (torch::%t-result out (list ta)
                      (lambda (g) (list (linalg:div g (linalg:mul 2.0 out)))))))

(defun torch:tanh (a)
  ;; Differentiable elementwise hyperbolic tangent (d/dx tanh x = 1 - tanh^2).
  (let* ((ta (torch::%t-wrap a)) (out (torch::%t-rtanh (aref ta 1))))
    (torch::%t-result out (list ta)
     (lambda (g) (list (linalg:mul g (linalg:sub 1.0 (linalg:mul out out))))))))

(defun torch:relu (a)
  ;; Differentiable elementwise max(x, 0.0); the gradient passes where x > 0
  ;; and is 0 elsewhere (0 at x = 0, like PyTorch).
  (let* ((ta (torch::%t-wrap a)) (xa (aref ta 1)))
    (torch::%t-result (linalg:relu xa) (list ta)
     (lambda (g) (list (linalg:mul g (linalg:greater xa 0.0)))))))

;; --- the matrix product ------------------------------------------------------

(defun torch::%t-swap-last (x)
  ;; x with its last two axes exchanged: the plain matrix transpose at rank 2,
  ;; the axes form of linalg:transpose on a stack.
  (let ((rank (length (array-dimensions x))))
    (if (< rank 3)
        (linalg:transpose x)
        (let ((axes nil))
          (do ((k (- rank 3) (- k 1)))
              ((< k 0))
            (setq axes (cons k axes)))
          (linalg:transpose x (append axes (list (- rank 1) (- rank 2))))))))

(defun torch::%t-mm-grad-a (g xa xb ra rb)
  ;; The matmul adjoint for the LEFT operand, by the operands' ranks: both
  ;; vectors (a dot product, g scalar), a vector right side (g gains the
  ;; column axis back), a vector left side (contract g against b's rows), and
  ;; the general stacked case g . b^T -- each unbroadcast over the batch axes.
  (cond ((and (= ra 1) (= rb 1)) (linalg:mul xb g))
   ((= rb 1)
    (torch::%t-unbroadcast (linalg:mul (linalg:expand-dims g -1) xb) xa))
   ((= ra 1)
    (torch::%t-unbroadcast
     (linalg:sum (linalg:mul xb (linalg:expand-dims g -2)) :axis -1) xa))
   (t (torch::%t-unbroadcast (linalg:matmul g (torch::%t-swap-last xb)) xa))))

(defun torch::%t-mm-grad-b (g xa xb ra rb)
  ;; The matmul adjoint for the RIGHT operand: the mirror of %t-mm-grad-a
  ;; (a^T . g in the general stacked case).
  (cond ((and (= ra 1) (= rb 1)) (linalg:mul xa g))
   ((= ra 1)
    (torch::%t-unbroadcast (linalg:mul (linalg:reshape xa
                                        (list (car (array-dimensions xa)) 1))
                                       (linalg:expand-dims g -2)) xb))
   ((= rb 1)
    (torch::%t-unbroadcast (linalg:mul xa (linalg:expand-dims g -1)) xb))
   (t (torch::%t-unbroadcast (linalg:matmul (torch::%t-swap-last xa) g) xb))))

(defun torch:matmul (a b)
  ;; Differentiable matrix product with torch.matmul's rank rules: two vectors
  ;; give the dot product (a scalar tensor), a matrix and a vector the usual
  ;; products, and rank >= 3 on either side the BATCHED product
  ;; (linalg:matmul: the last two axes are the matrix, leading axes
  ;; broadcast). Gradients flow to both operands, with the batch axes
  ;; unbroadcast like every other adjoint.
  (let* ((ta (torch::%t-wrap a))
         (tb (torch::%t-wrap b))
         (xa (aref ta 1))
         (xb (aref tb 1))
         (ra (length (array-dimensions xa)))
         (rb (length (array-dimensions xb)))
         (out
          (if (and (= ra 1) (= rb 1))
              (linalg:dot xa xb)
              (linalg:matmul xa xb))))
    (torch::%t-result out (list ta tb)
                      (lambda (g)
                        (list (when (torch::%t-track-p ta)
                                (torch::%t-mm-grad-a g xa xb ra rb))
                              (when (torch::%t-track-p tb)
                                (torch::%t-mm-grad-b g xa xb ra rb)))))))

;; --- shape operations --------------------------------------------------------

(defun torch:reshape (a shape)
  ;; Differentiable reshape (row-major, one extent may be -1: linalg:reshape);
  ;; the adjoint reshapes the gradient back.
  (let* ((ta (torch::%t-wrap a)) (xa (aref ta 1)))
    (torch::%t-result (linalg:reshape xa shape) (list ta)
                      (lambda (g) (list (torch::%t-grad-reshape g xa))))))

(defun torch:view (a shape)
  ;; PyTorch's other reshape spelling. rontolisp arrays are always contiguous
  ;; and every linalg result is a fresh copy, so view IS torch:reshape here --
  ;; it does not alias storage.
  (torch:reshape a shape))

(defun torch:transpose (a &optional axes)
  ;; Differentiable transpose: with no axes the matrix transpose (a vector
  ;; passes through, like linalg:transpose); with an axes list the rank-n
  ;; permutation (out-dims[k] = dims[axes[k]], a negative axis counting from
  ;; the end). The adjoint applies the INVERSE permutation to the gradient.
  (let* ((ta (torch::%t-wrap a))
         (xa (aref ta 1))
         (rank (length (array-dimensions xa)))
         (nx
          (if (null axes)
              nil
              (mapcar (lambda (v) (if (< v 0) (+ v rank) v)) axes))))
    (torch::%t-result (linalg:transpose xa nx) (list ta)
                      (lambda (g)
                        (list
                         (if (null nx)
                             (linalg:transpose g)
                             (linalg:transpose g
                              (torch::%t-inverse-perm nx))))))))

(defun torch::%t-inverse-perm (axes)
  ;; The inverse of an axes permutation: inv[axes[k]] = k, as a list.
  (let* ((rank (length axes)) (inv (make-array rank :initial-element 0)))
    (do ((p axes (cdr p)) (k 0 (+ k 1)))
        ((null p))
      (setf (aref inv (car p)) k))
    (let ((out nil))
      (do ((k (- rank 1) (- k 1)))
          ((< k 0) out)
        (setq out (cons (aref inv k) out))))))

(defun torch:unsqueeze (a axis)
  ;; Differentiable extent-1 axis insertion (linalg:expand-dims; a negative
  ;; axis counts from the end of the result, so -1 appends).
  (let* ((ta (torch::%t-wrap a)) (xa (aref ta 1)))
    (torch::%t-result (linalg:expand-dims xa axis) (list ta)
                      (lambda (g) (list (torch::%t-grad-reshape g xa))))))

(defun torch:squeeze (a &key axis)
  ;; Differentiable extent-1 axis removal (linalg:squeeze: all of them with no
  ;; :axis, else the named one(s); squeezing every axis away yields the SCALAR
  ;; tensor).
  (let* ((ta (torch::%t-wrap a)) (xa (aref ta 1)))
    (torch::%t-result (linalg:squeeze xa :axis axis) (list ta)
                      (lambda (g) (list (torch::%t-grad-reshape g xa))))))

(defun torch::%t-axis-spec (ax start end)
  ;; The linalg:slice specs list selecting (start end) on axis ax and leaving
  ;; every earlier axis whole.
  (let ((out (list (list start end))))
    (do ((k 0 (+ k 1)))
        ((>= k ax) out)
      (setq out (cons nil out)))))

(defun torch:cat (tensors &key (axis 0))
  ;; Differentiable concatenation of the LIST tensors along an EXISTING axis
  ;; (linalg:concatenate, torch.cat). The adjoint slices the gradient back
  ;; into each input's extent along that axis.
  (let* ((ts (torch::%t-wrap-all tensors))
         (xs (mapcar (lambda (tn) (aref tn 1)) ts))
         (ax (linalg::%la-norm-axis (array-dimensions (car xs)) axis)))
    (torch::%t-result (linalg:concatenate xs :axis ax) ts
                      (lambda (g)
                        (let ((start 0) (grads nil))
                          (do ((p xs (cdr p)))
                              ((null p) (reverse grads))
                            (let ((ext (nth ax (array-dimensions (car p)))))
                              (setq grads
                                    (cons (linalg:slice g
                                                        (torch::%t-axis-spec ax
                                                         start (+ start ext)))
                                          grads))
                              (setq start (+ start ext)))))))))

(defun torch:stack (tensors &key (axis 0))
  ;; Differentiable join of the LIST tensors along a NEW axis (linalg:stack).
  ;; The adjoint slices the gradient at each input's index along the new axis
  ;; and drops that axis again.
  (let* ((ts (torch::%t-wrap-all tensors))
         (xs (mapcar (lambda (tn) (aref tn 1)) ts))
         (d (array-dimensions (car xs)))
         (rank (length d))
         (ax (if (< axis 0) (+ axis rank 1) axis)))
    (torch::%t-result (linalg:stack xs :axis ax) ts
                      (lambda (g)
                        (let ((grads nil))
                          (do ((p xs (cdr p)) (i 0 (+ i 1)))
                              ((null p) (reverse grads))
                            (setq grads
                                  (cons (linalg:reshape
                                         (linalg:slice g
                                          (torch::%t-axis-spec ax i (+ i 1)))
                                         (array-dimensions (car p)))
                                        grads))))))))

(defun torch:slice (a specs)
  ;; Differentiable numpy basic slicing (linalg:slice: one spec per axis, nil
  ;; = whole axis, (start end) / (start end step), negative indexing). The
  ;; adjoint scatters the gradient back into zeros at the positions the slice
  ;; read from.
  (let* ((ta (torch::%t-wrap a)) (xa (aref ta 1)))
    (torch::%t-result (linalg:slice xa specs) (list ta)
     (lambda (g) (list (torch::%t-slice-scatter g xa specs))))))

(defun torch::%t-slice-scatter (g x specs)
  ;; Zeros shaped like x with g's elements ADDED back at the positions
  ;; linalg:slice read them from: the same bound normalization
  ;; (linalg::%la-slice-bound) and the same odometer walk, driving the
  ;; DESTINATION flat index by the slice strides.
  (let* ((d (array-dimensions x))
         (sx (linalg::%la-strides d))
         (os nil)
         (base 0))
    (do ((pd d (cdr pd)) (ps specs (cdr ps)) (pt sx (cdr pt)))
        ((null pd))
      (let ((n (car pd)) (spec (if ps (car ps) nil)))
        (if (null spec)
            (setq os (cons (car pt) os))
            (let* ((step (if (cddr spec) (caddr spec) 1))
                   (s0 (linalg::%la-slice-bound (car spec) n step t)))
              (setq os (cons (* step (car pt)) os))
              (setq base (+ base (* s0 (car pt))))))))
    (let* ((z (linalg::%la-like x))
           (n (array-total-size g))
           (rdims (reverse (array-dimensions g)))
           (idx (linalg::%la-zero-counters (length rdims)))
           (dst base))
      (do ((k 0 (+ k 1)))
          ((>= k n) z)
        (setf (row-major-aref z dst)
              (+ (row-major-aref z dst) (row-major-aref g k)))
        (do ((pc idx (cdr pc)) (pd rdims (cdr pd)) (ps os (cdr ps)) (carry t))
            ((or (null pc) (not carry)))
          (rplaca pc (+ (car pc) 1))
          (setq dst (+ dst (car ps)))
          (if (< (car pc) (car pd))
              (setq carry nil)
              (progn
                (rplaca pc 0)
                (setq dst (- dst (* (car pd) (car ps)))))))))))

;; --- reductions --------------------------------------------------------------

(defun torch:sum (a &key axis keepdims)
  ;; Differentiable sum, whole-tensor or along an axis (linalg:sum's
  ;; :axis/:keepdims rules). The adjoint broadcasts the gradient back over the
  ;; reduced extent.
  (let* ((ta (torch::%t-wrap a)) (xa (aref ta 1)))
    (if (numberp xa)
        ta
        (let* ((ax
                (if axis
                    (linalg::%la-norm-axis (array-dimensions xa) axis)
                    nil))
               (out
                (cond ((null axis)
                       (if keepdims
                           (linalg:sum xa :keepdims t)
                           (linalg:sum xa)))
                      (t (linalg:sum xa :axis ax :keepdims keepdims)))))
          (torch::%t-result out (list ta)
           (lambda (g) (list (torch::%t-grad-bcast g xa ax))))))))

(defun torch:mean (a &key axis keepdims)
  ;; Differentiable arithmetic mean (linalg:mean); the adjoint is the sum
  ;; adjoint divided by the reduced element count.
  (let* ((ta (torch::%t-wrap a)) (xa (aref ta 1)))
    (if (numberp xa)
        ta
        (let* ((d (array-dimensions xa))
               (ax (if axis (linalg::%la-norm-axis d axis) nil))
               (n (if (null axis) (linalg:size xa) (nth ax d)))
               (out
                (cond ((null axis)
                       (if keepdims
                           (linalg:mean xa :keepdims t)
                           (linalg:mean xa)))
                      (t (linalg:mean xa :axis ax :keepdims keepdims)))))
          (torch::%t-result out (list ta)
                            (lambda (g)
                              (list
                               (linalg:div (torch::%t-grad-bcast g xa ax)
                                           n))))))))

(defun torch:var (a &key axis keepdims (ddof 0))
  ;; Differentiable variance with the (n - :ddof) divisor (linalg:var's rules:
  ;; ddof 0 = torch's unbiased=False, ddof 1 = the sample variance). COMPOSED
  ;; from torch:mean/sub/mul/sum/div, so its backward comes from the tape
  ;; rather than a bespoke adjoint.
  (let* ((ta (torch::%t-wrap a))
         (xa (aref ta 1))
         (n
          (cond ((numberp xa) 1)
                ((null axis) (linalg:size xa))
                (t (let ((d (array-dimensions xa)))
                     (nth (linalg::%la-norm-axis d axis) d)))))
         (m
          (if (null axis)
              (torch:mean ta)
              (torch:mean ta :axis axis :keepdims t)))
         (dev (torch:sub ta m))
         (sq (torch:mul dev dev))
         (s
          (if (null axis)
              (torch:sum sq :keepdims keepdims)
              (torch:sum sq :axis axis :keepdims keepdims))))
    (torch:div s (- n ddof))))

(defun torch:std (a &key axis keepdims (ddof 0))
  ;; Differentiable standard deviation: torch:sqrt of torch:var.
  (torch:sqrt (torch:var a :axis axis :keepdims keepdims :ddof ddof)))

(defun torch:amax (a &key axis keepdims)
  ;; Differentiable maximum, whole-tensor or along an axis (linalg:amax). The
  ;; gradient flows to every element equal to the maximum, split EVENLY among
  ;; ties (PyTorch's amax rule).
  (let* ((ta (torch::%t-wrap a)) (xa (aref ta 1)))
    (if (numberp xa)
        ta
        (let* ((ax
                (if axis
                    (linalg::%la-norm-axis (array-dimensions xa) axis)
                    nil))
               (out
                (cond ((null axis)
                       (if keepdims
                           (linalg:amax xa :keepdims t)
                           (linalg:amax xa)))
                      (t (linalg:amax xa :axis ax :keepdims keepdims)))))
          (torch::%t-result out (list ta)
                            (lambda (g)
                              (let* ((mask
                                      (linalg:equal xa
                                       (torch::%t-keepdims out xa ax)))
                                     (cnt
                                      (if (null ax)
                                          (linalg:sum mask)
                                          (linalg:sum mask
                                                      :axis ax
                                                      :keepdims t))))
                                (list
                                 (linalg:div
                                  (linalg:mul (torch::%t-keepdims g xa ax) mask)
                                  cnt)))))))))

(defun torch:argmax (a &key axis)
  ;; NON-differentiable: the index of the largest element (linalg:argmax) --
  ;; the integer index for a vector, the per-slice index array with :axis --
  ;; returned as a raw linalg value, not a tensor.
  (let ((xa (aref (torch::%t-wrap a) 1)))
    (if (null axis) (linalg:argmax xa) (linalg:argmax xa :axis axis))))

;; --- softmax -----------------------------------------------------------------

(defun torch:softmax (a &key axis)
  ;; Differentiable max-subtracted softmax (linalg:softmax: the whole tensor
  ;; is one distribution with no :axis, one distribution per slice with an
  ;; integer :axis -- torch's softmax(x, dim)). The adjoint is
  ;; s * (g - sum(g * s)) over each distribution.
  (let* ((ta (torch::%t-wrap a)) (xa (aref ta 1)))
    (let* ((ax (if axis (linalg::%la-norm-axis (array-dimensions xa) axis) nil))
           (out
            (if (null axis) (linalg:softmax xa) (linalg:softmax xa :axis ax))))
      (torch::%t-result out (list ta)
                        (lambda (g)
                          (let ((tot
                                 (if (null ax)
                                     (linalg:sum (linalg:mul g out))
                                     (linalg:sum (linalg:mul g out)
                                                 :axis ax
                                                 :keepdims t))))
                            (list (linalg:mul out (linalg:sub g tot)))))))))

(defun torch:log-softmax (a &key axis)
  ;; Differentiable log-softmax (linalg:log-softmax, the numerically stable
  ;; half of a cross-entropy loss). The adjoint is g - softmax(x) * sum(g),
  ;; with softmax(x) recovered as exp of the forward result.
  (let* ((ta (torch::%t-wrap a)) (xa (aref ta 1)))
    (let* ((ax (if axis (linalg::%la-norm-axis (array-dimensions xa) axis) nil))
           (out
            (if (null axis)
                (linalg:log-softmax xa)
                (linalg:log-softmax xa :axis ax))))
      (torch::%t-result out (list ta)
                        (lambda (g)
                          (let ((tot
                                 (if (null ax)
                                     (linalg:sum g)
                                     (linalg:sum g :axis ax :keepdims t))))
                            (list
                             (linalg:sub g
                              (linalg:mul (linalg:exp out) tot)))))))))

;; --- masking and index selection ---------------------------------------------

(defun torch:masked-fill (a mask value)
  ;; Differentiable masked fill: the scalar value where mask is NON-ZERO, a's
  ;; element where it is zero (torch.masked_fill over linalg:where, so filling
  ;; attention scores with -infinity before torch:softmax is safe). mask and
  ;; value are constants -- no gradient flows to them.
  (let* ((ta (torch::%t-wrap a))
         (xa (aref ta 1))
         (m (if (torch:tensorp mask) (aref mask 1) mask))
         (v (if (torch:tensorp value) (aref value 1) value)))
    (torch::%t-result (linalg:where m v xa) (list ta)
     (lambda (g) (list (torch::%t-unbroadcast (linalg:where m 0.0 g) xa))))))

(defun torch::%t-indices (idx)
  ;; An index operand -- a tensor, an index vector or a list -- as the raw
  ;; vector linalg's index functions read.
  (cond ((torch:tensorp idx) (aref idx 1))
        ((consp idx) (linalg:from-list idx))
        (t idx)))

(defun torch:gather (a idx)
  ;; Differentiable per-row selection of a matrix: element a[i, idx[i]] as a
  ;; vector (linalg:gather -- the cross-entropy "pick the target logit"
  ;; idiom). The adjoint scatters the gradient back to the picked cells.
  (let* ((ta (torch::%t-wrap a)) (xa (aref ta 1)) (iv (torch::%t-indices idx)))
    (torch::%t-result (linalg:gather xa iv) (list ta)
                      (lambda (g)
                        (let ((z (linalg::%la-like xa)) (n (length iv)))
                          (do ((i 0 (+ i 1)))
                              ((>= i n))
                            (let ((j (truncate (aref iv i))))
                              (setf (aref z i j) (+ (aref z i j) (aref g i)))))
                          (list z))))))

(defun torch:index-select (a idx)
  ;; Differentiable axis-0 slice selection (linalg:take-rows) -- the embedding
  ;; lookup: row idx[i] of the table for each i, any rank >= 1, indices may
  ;; repeat. The adjoint scatter-ADDS each output slab's gradient back into
  ;; its source row, so a row selected twice accumulates both contributions.
  (let* ((ta (torch::%t-wrap a)) (xa (aref ta 1)) (iv (torch::%t-indices idx)))
    (torch::%t-result (linalg:take-rows xa iv) (list ta)
                      (lambda (g)
                        (let* ((z (linalg::%la-like xa))
                               (slab
                                (linalg::%la-tail-size (array-dimensions xa) 0))
                               (m (length iv)))
                          (do ((i 0 (+ i 1)))
                              ((>= i m))
                            (let ((dst (* (truncate (aref iv i)) slab))
                                  (src (* i slab)))
                              (do ((k 0 (+ k 1)))
                                  ((>= k slab))
                                (setf (row-major-aref z (+ dst k))
                                      (+ (row-major-aref z (+ dst k))
                                         (row-major-aref g (+ src k)))))))
                          (list z))))))
