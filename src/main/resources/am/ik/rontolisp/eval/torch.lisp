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

(defun torch:set-data (tn value)
  ;; Replaces the tensor's data IN PLACE with value (a linalg array or a number)
  ;; and returns the tensor: the parameter update of a training loop, applied to
  ;; the very tensor a module's fields already point at, so the layer keeps
  ;; using it. The tape is untouched -- call it inside torch:no-grad, like
  ;; torch.no_grad() around an optimizer step.
  (torch::%t-check tn)
  (setf (aref tn 1) value)
  tn)

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

(defun torch:zero-grad (x)
  ;; Clears accumulated gradients and returns the argument: for a TENSOR its own
  ;; grad slot, for a MODULE the grad slot of every parameter torch:parameters
  ;; reaches, for an OPTIMIZER the grad slot of every parameter it was built
  ;; over -- the between-steps call of a training loop, under any of the three
  ;; spellings PyTorch has for it.
  (cond ((torch:tensorp x)
         (setf (aref x 2) nil)
         x)
   ((torch:modulep x)
    (do ((p (torch:parameters x) (cdr p)))
        ((null p) x)
      (setf (aref (car p) 2) nil)))
   ((torch:optimizerp x)
    (do ((p (aref x 2) (cdr p)))
        ((null p) x)
      (setf (aref (car p) 2) nil)))
   (t (error "torch: zero-grad expects a tensor, a module or an optimizer"))))

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

;; --- the module protocol -----------------------------------------------------
;; A module is the second fixed-layout record of this package (five slots, so it
;; never collides with the six-slot tensor): a named bag of FIELDS plus a forward
;; function. Like the tensor it is built from plain defuns -- no defclass, no
;; defmethod -- so LibraryDefunPruner keeps pruning every definition here (see
;; the header and .kb/torch.md), and torch:forward dispatches through the
;; module's own closure rather than through generic-function dispatch.
;;
;;   slot 0  tag         the symbol torch::%module (torch:modulep discriminates)
;;   slot 1  kind        a keyword naming the layer (:linear, :sequential, ...)
;;   slot 2  fields      a plist -- KEYWORD value keyword value ... -- holding
;;                       EVERY parameter, buffer, submodule and hyper-parameter
;;                       of the layer; this is what torch:parameters walks
;;   slot 3  forward-fn  (lambda (self args...) ...) -> the output tensor
;;   slot 4  training    the train/eval flag (t = training, nil = eval)
;;
;; The fields plist is the module's parameter registration -- the defun-only
;; equivalent of walking a CLOS instance's slots: a layer's forward reads its
;; parameters back out of it with torch:field (never from a closed-over
;; variable), so a parameter that exists cannot be missing from the plist. A
;; forward reading a parameter from its closure while the plist did not list it
;; would train silently wrong, which is the failure mode this layout removes.
;; Field names and kinds are KEYWORDS so they read identically from any package
;; (a plain symbol would be cl-user::weight in a user program and never eq to
;; the torch::weight the library wrote).

(defun torch::%m-new (kind fields forward-fn training)
  ;; The one constructor of the five-slot record described above.
  (let ((m (make-array 5 :initial-element nil)))
    (setf (aref m 0) 'torch::%module)
    (setf (aref m 1) kind)
    (setf (aref m 2) fields)
    (setf (aref m 3) forward-fn)
    (setf (aref m 4) training)
    m))

(defun torch:module (kind fields forward-fn)
  ;; A fresh module of the given kind (a keyword), fields (a plist of
  ;; KEYWORD/value pairs: parameters, buffers, submodules, lists of submodules
  ;; and plain hyper-parameters) and forward function -- called as
  ;; (funcall forward-fn module args...) by torch:forward. Starts in training
  ;; mode. This is how a user layer is written; the built-in layers below are
  ;; ordinary callers of it.
  (torch::%m-new kind fields forward-fn t))

(defun torch:modulep (x)
  ;; Whether x is a torch module: the fixed-layout five-slot general vector
  ;; whose first slot is the tag symbol.
  (if (and (arrayp x) (not (stringp x)) (equal (array-dimensions x) '(5))
           (eq (row-major-aref x 0) 'torch::%module))
      t
      nil))

(defun torch::%m-check (x)
  ;; Signals unless x is a module; returns it.
  (unless (torch:modulep x) (error "torch: expected a module"))
  x)

(defun torch:module-kind (m)
  ;; The module's kind symbol, as given to torch:module.
  (torch::%m-check m)
  (aref m 1))

(defun torch::%m-fields-slot (x)
  ;; The slot holding the fields plist -- 2 for a module, 3 for an optimizer,
  ;; the two records built around one. Signals for anything else.
  (cond ((torch:modulep x) 2)
        ((torch:optimizerp x) 3)
        (t (error "torch: expected a module or an optimizer"))))

(defun torch::%m-cell (plist name)
  ;; The plist cell whose car is name, or nil when there is no such field -- so
  ;; a present-but-nil field is distinguishable from a missing one.
  (do ((p plist (cddr p)))
      ((null p) nil)
    (when (eq (car p) name) (return p))))

(defun torch:field (m name)
  ;; The value of the named field of a MODULE (a parameter, a submodule, a list
  ;; of submodules or a hyper-parameter) or of an OPTIMIZER (a hyper-parameter
  ;; such as :lr, or a state buffer), name being the field's KEYWORD. Signals
  ;; when there is no such field, so a misspelled name is loud rather than
  ;; silently nil.
  (let* ((s (torch::%m-fields-slot m)) (c (torch::%m-cell (aref m s) name)))
    (if (null c) (error "torch: no such field") (cadr c))))

(defun torch:set-field (m name value)
  ;; Sets the named field of a module or an optimizer, adding it when it is
  ;; new; returns the record. Replacing a parameter this way is what re-binds a
  ;; layer to a given set of weights (the gradient check does exactly that);
  ;; on an optimizer it is the learning-rate knob a schedule turns.
  (let* ((s (torch::%m-fields-slot m)) (c (torch::%m-cell (aref m s) name)))
    (if (null c)
        (setf (aref m s) (append (aref m s) (list name value)))
        (rplaca (cdr c) value)))
  m)

(defun torch:forward (m &rest args)
  ;; Runs a module's forward pass -- (funcall its forward-fn module args...) --
  ;; and returns the output tensor. A plain FUNCTION is also accepted and simply
  ;; applied, so a stateless step (an activation, a reshape) can sit in a
  ;; torch:sequential without a wrapper layer existing for it.
  (cond ((torch:modulep m) (apply (aref m 3) m args))
        ((functionp m) (apply m args))
        (t (error "torch: forward expects a module or a function"))))

(defun torch:parameter (x &key element-type)
  ;; A leaf tensor with :requires-grad t -- the spelling that marks a value as a
  ;; trainable parameter of a module. A field holding a tensor WITHOUT
  ;; requires-grad is a buffer instead: torch:parameters skips it.
  (torch:tensor x :requires-grad t :element-type element-type))

(defun torch::%m-collect (v acc)
  ;; The parameter walk over one field value, prepending onto acc: a parameter
  ;; tensor (a leaf with requires-grad) is collected once by IDENTITY -- a weight
  ;; shared by two layers appears a single time -- a module recurses into its own
  ;; fields, a list recurses element-wise, and anything else contributes nothing.
  (cond ((torch:tensorp v)
         (if (and (aref v 3) (not (member v acc))) (cons v acc) acc))
        ((torch:modulep v) (torch::%m-collect-fields (aref v 2) acc))
        ((consp v)
         (do ((p v (cdr p)) (a acc (torch::%m-collect (car p) a)))
             ((null p) a)))
        (t acc)))

(defun torch::%m-collect-fields (plist acc)
  ;; torch::%m-collect over the VALUES of a fields plist (the names are skipped).
  (do ((p plist (cddr p)) (a acc (torch::%m-collect (cadr p) a))) ((null p) a)))

(defun torch:parameters (m)
  ;; Every parameter reachable from the module, in registration order and
  ;; deduplicated by identity: its own parameter fields, then those of its
  ;; submodules and of any list of submodules it holds, recursively. This is the
  ;; list an optimizer is built over. Reaching a parameter needs no declaration
  ;; beyond putting it in the fields plist.
  (torch::%m-check m)
  (reverse (torch::%m-collect-fields (aref m 2) nil)))

(defun torch::%m-set-mode (v mode)
  ;; Sets the training flag on every module reachable from a field value.
  (cond ((torch:modulep v)
         (setf (aref v 4) mode)
         (do ((p (aref v 2) (cddr p)))
             ((null p))
           (torch::%m-set-mode (cadr p) mode)))
        ((torch:tensorp v) nil)
        ((consp v)
         (do ((p v (cdr p)))
             ((null p))
           (torch::%m-set-mode (car p) mode)))
        (t nil)))

(defun torch:train (m &optional (mode t))
  ;; Puts the module and every submodule into TRAINING mode (nn.Module.train);
  ;; an explicit nil mode is the same as torch:eval. Returns the module. Only
  ;; torch:dropout reads the flag.
  (torch::%m-check m)
  (torch::%m-set-mode m mode)
  m)

(defun torch:eval (m)
  ;; Puts the module and every submodule into EVALUATION mode
  ;; (nn.Module.eval): dropout becomes the identity. Returns the module.
  (torch::%m-check m)
  (torch::%m-set-mode m nil)
  m)

(defun torch:training-p (m)
  ;; Whether the module is in training mode.
  (torch::%m-check m)
  (if (aref m 4) t nil))

;; --- the layers --------------------------------------------------------------

(defun torch::%m-linear-forward (self x)
  ;; y = x . W (+ b), with W stored as (in-features out-features) so the forward
  ;; is a plain torch:matmul and the bias broadcasts over every leading axis.
  (let ((y (torch:matmul x (torch:field self :weight)))
        (b (torch:field self :bias)))
    (if (null b) y (torch:add y b))))

(defun torch:linear (in-features out-features &key (bias t))
  ;; A fully connected layer (nn.Linear): fields weight, an
  ;; (in-features out-features) parameter, and bias, an (out-features) parameter
  ;; or nil under :bias nil. Both are drawn from PyTorch's default
  ;; U(-1/sqrt(in-features), 1/sqrt(in-features)) using the seeded linalg
  ;; generator, so linalg:seed reproduces a run on every backend. The weight is
  ;; stored (in out) -- not PyTorch's transposed (out in) -- so the forward is a
  ;; plain matmul.
  (let* ((bound (/ 1.0 (sqrt (* 1.0 in-features))))
         (w
          (torch:parameter
           (linalg:uniform (- bound) bound (list in-features out-features))))
         (b
          (if bias
              (torch:parameter
               (linalg:uniform (- bound) bound (list out-features)))
              nil)))
    (torch:module :linear (list :weight w :bias b)
                  (function torch::%m-linear-forward))))

(defun torch::%m-embedding-forward (self idx)
  ;; Row lookup: indices of any shape (d...) select rows of the
  ;; (num-embeddings embedding-dim) table into (d... embedding-dim).
  (let* ((w (torch:field self :weight))
         (iv (torch::%t-indices idx))
         (d (array-dimensions iv))
         (dim (cadr (torch:shape w))))
    (if (= (length d) 1)
        (torch:index-select w iv)
        (torch:reshape
         (torch:index-select w (linalg:reshape iv (list (array-total-size iv))))
         (append d (list dim))))))

(defun torch:embedding (num-embeddings embedding-dim)
  ;; An embedding table (nn.Embedding): the single field weight, a
  ;; (num-embeddings embedding-dim) parameter drawn from the standard normal
  ;; like PyTorch's default. The forward takes integer indices of any shape and
  ;; returns them with the embedding axis appended; a row selected twice
  ;; accumulates both gradients (torch:index-select's adjoint).
  (let ((w
         (torch:parameter (linalg:randn (list num-embeddings embedding-dim)))))
    (torch:module :embedding (list :weight w)
                  (function torch::%m-embedding-forward))))

(defun torch::%m-sequential-forward (self x)
  ;; Threads the input through each element in order.
  (let ((out x))
    (do ((p (torch:field self :layers) (cdr p)))
        ((null p) out)
      (setq out (torch:forward (car p) out)))))

(defun torch:sequential (&rest layers)
  ;; A chain of layers (nn.Sequential): the forward threads its argument through
  ;; each element in order. An element may be a module OR a plain function, so an
  ;; activation goes in as (function torch:relu) -- there is no separate
  ;; activation-module type in this package. The elements live in the single
  ;; field layers, and torch:parameters walks that list, so every nested
  ;; parameter is reachable.
  (torch:module :sequential (list :layers layers)
                (function torch::%m-sequential-forward)))

(defun torch::%m-layer-norm-forward (self x)
  ;; (x - mean) / sqrt(var + eps) * weight + bias over the LAST axis, with the
  ;; biased (ddof 0) variance -- PyTorch's unbiased=False.
  (let* ((tx (torch::%t-wrap x))
         (mu (torch:mean tx :axis -1 :keepdims t))
         (dev (torch:sub tx mu))
         (v (torch:var tx :axis -1 :keepdims t :ddof 0))
         (norm
          (torch:div dev (torch:sqrt (torch:add v (torch:field self :eps))))))
    (torch:add (torch:mul norm (torch:field self :weight))
               (torch:field self :bias))))

(defun torch:layer-norm (d-model &key (eps 1.0e-5))
  ;; Layer normalization over the last axis (nn.LayerNorm): fields weight (a
  ;; (d-model) parameter of ones), bias (a (d-model) parameter of zeros) and the
  ;; eps hyper-parameter. The variance uses the (n - 0) divisor, and the whole
  ;; expression is composed from torch ops, so the normalization itself is
  ;; differentiable -- the gradient flows through the mean and the variance too.
  (let ((g (torch:parameter (linalg:ones (list d-model))))
        (b (torch:parameter (linalg:zeros (list d-model)))))
    (torch:module :layer-norm (list :weight g :bias b :eps eps)
                  (function torch::%m-layer-norm-forward))))

(defun torch::%m-dropout-forward (self x)
  ;; INVERTED dropout, like PyTorch: in training mode each element survives with
  ;; probability 1 - p and the survivors are scaled by 1 / (1 - p), so the
  ;; expectation is unchanged and evaluation needs no rescaling at all.
  (let ((p (torch:field self :p)) (tx (torch::%t-wrap x)))
    (if (or (null (aref self 4)) (<= p 0))
        tx
        (torch:mul tx
                   (linalg:div (linalg:greater (linalg:rand (torch:shape tx)) p)
                               (- 1.0 p))))))

(defun torch:dropout (p)
  ;; A dropout layer (nn.Dropout) with drop probability p, in the single field
  ;; p. In TRAINING mode it zeroes each element with probability p and scales
  ;; the survivors by 1 / (1 - p); in EVALUATION mode (torch:eval) it is the
  ;; identity. The mask comes from the seeded linalg generator, so linalg:seed
  ;; reproduces a training run on every backend.
  (torch:module :dropout (list :p p) (function torch::%m-dropout-forward)))

;; --- the losses --------------------------------------------------------------

(defun torch::%m-reduce-loss (v reduction)
  ;; The :reduction keyword shared by the losses: :none keeps the per-element
  ;; tensor, :sum adds it up, and anything else (the :mean default) is handled
  ;; by the caller, which knows the right denominator.
  (if (eq reduction :none) v (torch:sum v)))

(defun torch:mse-loss (input target &key (reduction :mean))
  ;; Mean squared error (nn.MSELoss): the mean of (input - target)^2 as a scalar
  ;; tensor. :reduction :sum adds instead of averaging, :none returns the
  ;; per-element tensor. target is a constant unless it is itself a tensor
  ;; requiring gradients.
  (let* ((diff (torch:sub input target)) (sq (torch:mul diff diff)))
    (if (eq reduction :mean)
        (torch:mean sq)
        (torch::%m-reduce-loss sq reduction))))

(defun torch::%m-ce-indices (targets n)
  ;; The class-index operand of the cross entropy as a flat n-element vector: a
  ;; number, a list, an array or a tensor of any shape.
  (let ((iv (torch::%t-indices targets)))
    (cond ((numberp iv) (linalg:from-list (list iv)))
          ((= (length (array-dimensions iv)) 1) iv)
          (t (linalg:reshape iv (list n))))))

(defun torch::%m-ce-soft-p (shape targets)
  ;; Whether the target operand is a full probability DISTRIBUTION rather than
  ;; class indices: a tensor or a raw array whose shape equals the logits'.
  ;; A number, a list and an index vector are always class indices -- a list of
  ;; the class count would otherwise be ambiguous with an unbatched
  ;; distribution, so the probability spelling requires a tensor or an array.
  (let ((v
         (cond ((torch:tensorp targets) (aref targets 1))
               ((and (arrayp targets) (not (stringp targets))) targets)
               (t nil))))
    (if (or (null v) (numberp v)) nil (equal (array-dimensions v) shape))))

(defun torch::%m-ce-soft (x p n reduction)
  ;; The probability-target cross entropy of the flattened (n classes) logits x
  ;; against the matching distribution p: -sum(p * log-softmax(x)) per position.
  (let ((per
         (torch:neg
          (torch:sum (torch:mul p (torch:log-softmax x :axis 1)) :axis 1))))
    (if (eq reduction :mean)
        (torch:div (torch:sum per) n)
        (torch::%m-reduce-loss per reduction))))

(defun torch:cross-entropy-loss
    (logits targets &key ignore-index (reduction :mean))
  ;; Cross entropy over raw LOGITS (nn.CrossEntropyLoss): logits of shape
  ;; (... num-classes) -- the leading axes are flattened, so (batch seq vocab)
  ;; works directly. The target is either
  ;;
  ;;   * integer CLASS INDICES of the matching leading shape (a number, a list,
  ;;     an index vector or a tensor): computed as -log-softmax picked at the
  ;;     target class, which is the numerically stable form. :ignore-index k
  ;;     drops every position whose target is k from BOTH the sum and the mean's
  ;;     denominator (the padding positions of a batch must not contribute);
  ;;
  ;;   * or class PROBABILITIES -- a tensor or array of the logits' own shape,
  ;;     PyTorch's soft-label form: -sum(target * log-softmax(logits)) per
  ;;     position. :ignore-index does not apply to it (there is no single class
  ;;     to drop), exactly like PyTorch.
  ;;
  ;; :reduction :sum adds instead of averaging, :none returns the per-position
  ;; tensor.
  (let* ((tl (torch::%t-wrap logits))
         (d (torch:shape tl))
         (c (car (last d)))
         (n (linalg::%la-head-size d (- (length d) 1)))
         (x (if (= (length d) 2) tl (torch:reshape tl (list n c)))))
    (if (torch::%m-ce-soft-p d targets)
        (let ((tp (torch::%t-wrap targets)))
          (torch::%m-ce-soft x
           (if (= (length d) 2) tp (torch:reshape tp (list n c))) n reduction))
        (torch::%m-ce-hard x n targets ignore-index reduction))))

(defun torch::%m-ce-hard (x n targets ignore-index reduction)
  ;; The class-index cross entropy: -log-softmax gathered at the target class,
  ;; with the :ignore-index positions dropped from the sum and the denominator.
  (let* ((iv (torch::%m-ce-indices targets n))
         (drop (if (null ignore-index) nil (linalg:equal iv ignore-index)))
         (keep (if (null drop) nil (linalg:sub 1.0 drop)))
         (safe (if (null drop) iv (linalg:where drop 0.0 iv)))
         (picked (torch:neg (torch:gather (torch:log-softmax x :axis 1) safe)))
         (masked (if (null keep) picked (torch:mul picked keep))))
    (if (eq reduction :mean)
        (torch:div (torch:sum masked) (if (null keep) n (linalg:sum keep)))
        (torch::%m-reduce-loss masked reduction))))

;; --- the optimizers ----------------------------------------------------------
;; The THIRD fixed-layout record of this package, and the same defun-only
;; decision applied once more: an optimizer is a named bag of FIELDS plus a step
;; function, so LibraryDefunPruner keeps pruning it and a program that only
;; wants torch:sgd does not carry Adam's moments.
;;
;;   slot 0  tag         the symbol torch::%optimizer (torch:optimizerp
;;                       discriminates on it -- the LENGTH is only a shape
;;                       pre-check, and here it is six like a tensor's, so the
;;                       tag is what separates the two)
;;   slot 1  kind        a keyword naming the rule (:sgd, :adam, ...)
;;   slot 2  params      the list of parameter tensors this optimizer updates
;;   slot 3  fields      a plist -- KEYWORD value ... -- holding every
;;                       hyper-parameter AND every state buffer of the rule,
;;                       read and written with torch:field / torch:set-field
;;   slot 4  step-count  the optimizer's OWN step counter, incremented by
;;                       torch:step before the rule runs (Adam's bias
;;                       correction reads it, and must see 1 on the first step)
;;   slot 5  step-fn     (lambda (self) ...), applied by torch:step
;;
;; Like the module's fields, this plist is the single place the state lives:
;; the momentum buffer of an SGD and the m/v moments of an Adam are fields, so
;; nothing hangs off the parameter tensor and two optimizers over the same
;; parameters keep separate state. The rules update the parameter's data array
;; ELEMENT-WISE AND IN PLACE (see torch::%o-sgd-step): allocating a fresh array
;; per parameter per step is the allocation that dominates a small training
;; loop, and the optimizer runs outside the tape -- it uses no torch op, so no
;; adjoint is lost and no torch:no-grad is needed around torch:step.

(defun torch::%o-new (kind params fields step-fn)
  ;; The one constructor of the six-slot record described above.
  (let ((o (make-array 6 :initial-element nil)))
    (setf (aref o 0) 'torch::%optimizer)
    (setf (aref o 1) kind)
    (setf (aref o 2) params)
    (setf (aref o 3) fields)
    (setf (aref o 4) 0)
    (setf (aref o 5) step-fn)
    o))

(defun torch::%o-params (params)
  ;; The parameter list an optimizer is built over: a MODULE is walked with
  ;; torch:parameters, a list of tensors is taken as given.
  (if (torch:modulep params) (torch:parameters params) params))

(defun torch:optimizer (kind params fields step-fn)
  ;; A fresh optimizer of the given kind (a keyword) over params (a module,
  ;; whose torch:parameters are walked, or a plain list of parameter tensors),
  ;; with fields (a plist of KEYWORD/value hyper-parameters and state buffers)
  ;; and a step function called as (funcall step-fn optimizer) by torch:step.
  ;; The step counter starts at 0. This is how a user optimizer is written; the
  ;; built-in rules below are ordinary callers of it.
  (torch::%o-new kind (torch::%o-params params) fields step-fn))

(defun torch:optimizerp (x)
  ;; Whether x is a torch optimizer: the fixed-layout six-slot general vector
  ;; whose first slot is the optimizer tag.
  (if (and (arrayp x) (not (stringp x)) (equal (array-dimensions x) '(6))
           (eq (row-major-aref x 0) 'torch::%optimizer))
      t
      nil))

(defun torch::%o-check (x)
  ;; Signals unless x is an optimizer; returns it.
  (unless (torch:optimizerp x) (error "torch: expected an optimizer"))
  x)

(defun torch:optimizer-kind (o)
  ;; The optimizer's kind keyword, as given to torch:optimizer.
  (torch::%o-check o)
  (aref o 1))

(defun torch:optimizer-params (o)
  ;; The list of parameter tensors the optimizer updates -- what a step
  ;; function walks.
  (torch::%o-check o)
  (aref o 2))

(defun torch:step-count (o)
  ;; How many times torch:step has run: 0 before the first step, and the t of
  ;; Adam's bias correction during the step itself.
  (torch::%o-check o)
  (aref o 4))

(defun torch:step (o)
  ;; Applies the optimizer's rule to every parameter and returns the optimizer
  ;; (torch.optim.Optimizer.step). The step COUNTER is incremented FIRST, so a
  ;; bias correction reading torch:step-count sees 1 during the first step. The
  ;; update itself writes the parameter data in place with no torch op, so it
  ;; records nothing on the tape and needs no torch:no-grad around it.
  (torch::%o-check o)
  (setf (aref o 4) (+ (aref o 4) 1))
  (funcall (aref o 5) o)
  o)

(defun torch::%o-buffers (params)
  ;; One zero buffer per parameter, shaped like that parameter's data (a
  ;; one-element vector for a scalar parameter, whose data is a plain number):
  ;; SGD's momentum buffer and Adam's two moments. A general vector indexed by
  ;; the parameter's position in the optimizer's list, allocated on the first
  ;; step like PyTorch's lazily created state.
  (let* ((n (length params)) (v (make-array n :initial-element nil)))
    (do ((p params (cdr p)) (i 0 (+ i 1)))
        ((null p) v)
      (let ((x (aref (car p) 1)))
        (setf (aref v i)
         (if (numberp x) (linalg:zeros (list 1)) (linalg:zeros-like x)))))))

(defun torch::%o-buffer-field (self name)
  ;; The named state field, allocating it on first use from the optimizer's
  ;; parameter list.
  (let ((b (torch:field self name)))
    (if (null b)
        (let ((fresh (torch::%o-buffers (aref self 2))))
          (torch:set-field self name fresh)
          fresh)
        b)))

(defun torch::%o-sgd-step (self)
  ;; PyTorch's SGD rule, element-wise and IN PLACE over each parameter's data:
  ;;   g <- grad + weight-decay * param
  ;;   buf <- momentum * buf + g          (momentum /= 0; buf starts at zero,
  ;;                                       which is PyTorch's clone-on-first-
  ;;                                       step with dampening 0)
  ;;   param <- param - lr * (momentum /= 0 ? buf : g)
  ;; A parameter whose gradient is still nil (nothing reached it) is skipped,
  ;; like PyTorch's `if p.grad is None: continue`.
  (let ((lr (torch:field self :lr))
        (mu (torch:field self :momentum))
        (wd (torch:field self :weight-decay))
        (bufs nil))
    (unless (= mu 0) (setq bufs (torch::%o-buffer-field self :buffers)))
    (do ((ps (aref self 2) (cdr ps)) (i 0 (+ i 1)))
        ((null ps) self)
      (let* ((p (car ps)) (g (aref p 2)))
        (unless (null g)
          (let* ((x (aref p 1))
                 (sx (numberp x))
                 (sg (numberp g))
                 (n (if sx 1 (array-total-size x)))
                 (buf (if (null bufs) nil (aref bufs i))))
            (do ((k 0 (+ k 1)))
                ((>= k n))
              (let* ((xv (if sx x (row-major-aref x k)))
                     (gv (if sg g (row-major-aref g k)))
                     (d (if (= wd 0) gv (+ gv (* wd xv)))))
                (unless (null buf)
                  (setq d (+ (* mu (row-major-aref buf k)) d))
                  (setf (row-major-aref buf k) d))
                (let ((nv (- xv (* lr d))))
                  (if sx
                      (setf (aref p 1) nv)
                      (setf (row-major-aref x k) nv)))))))))))

(defun torch::%o-adam-step (self)
  ;; PyTorch's Adam rule, element-wise and IN PLACE:
  ;;   m <- b1 * m + (1 - b1) * g,  v <- b2 * v + (1 - b2) * g^2
  ;;   param <- param - lr * (m / (1 - b1^t)) / (sqrt(v / (1 - b2^t)) + eps)
  ;; t being the OPTIMIZER's own step count (torch:step-count), which is 1 on
  ;; the first step -- the classic off-by-one, pinned by the optimizer table.
  (let* ((lr (torch:field self :lr))
         (betas (torch:field self :betas))
         (b1 (car betas))
         (b2 (car (cdr betas)))
         (eps (torch:field self :eps))
         (it (aref self 4))
         (c1 (- 1.0 (expt b1 it)))
         (c2 (- 1.0 (expt b2 it)))
         (ms (torch::%o-buffer-field self :m))
         (vs (torch::%o-buffer-field self :v)))
    (do ((ps (aref self 2) (cdr ps)) (i 0 (+ i 1)))
        ((null ps) self)
      (let* ((p (car ps)) (g (aref p 2)))
        (unless (null g)
          (let* ((x (aref p 1))
                 (sx (numberp x))
                 (sg (numberp g))
                 (n (if sx 1 (array-total-size x)))
                 (m (aref ms i))
                 (v (aref vs i)))
            (do ((k 0 (+ k 1)))
                ((>= k n))
              (let* ((xv (if sx x (row-major-aref x k)))
                     (gv (if sg g (row-major-aref g k)))
                     (mk (+ (* b1 (row-major-aref m k)) (* (- 1.0 b1) gv)))
                     (vk (+ (* b2 (row-major-aref v k)) (* (- 1.0 b2) gv gv))))
                (setf (row-major-aref m k) mk)
                (setf (row-major-aref v k) vk)
                (let ((nv (- xv (/ (* lr (/ mk c1)) (+ (sqrt (/ vk c2)) eps)))))
                  (if sx
                      (setf (aref p 1) nv)
                      (setf (row-major-aref x k) nv)))))))))))

(defun torch:sgd (params &key (lr 0.01) (momentum 0.0) (weight-decay 0.0))
  ;; Stochastic gradient descent (torch.optim.SGD) over params (a module or a
  ;; list of parameter tensors): fields lr, momentum, weight-decay and the
  ;; momentum buffers. With :momentum 0 (the default) the update is plain
  ;; param -= lr * grad; :weight-decay adds the L2 term wd * param to the
  ;; gradient. Change a hyper-parameter mid-run with torch:set-field.
  (torch:optimizer :sgd params
   (list :lr lr :momentum momentum :weight-decay weight-decay :buffers nil)
   (function torch::%o-sgd-step)))

(defun torch:adam (params &key (lr 0.001) (betas '(0.9 0.999)) (eps 1.0e-8))
  ;; The Adam optimizer (torch.optim.Adam) over params (a module or a list of
  ;; parameter tensors): fields lr, betas (the two exponential decay rates, as
  ;; a list, PyTorch's (beta1, beta2) tuple), eps and the two moment buffers.
  ;; The bias correction divides by 1 - beta^t with the OPTIMIZER's own step
  ;; count, so the first step is fully corrected.
  (torch:optimizer :adam params
                   (list :lr lr :betas betas :eps eps :m nil :v nil)
                   (function torch::%o-adam-step)))

;; --- batching, padding and the attention masks -------------------------------
;; Plain functions rather than a Dataset/DataLoader hierarchy: a batch here is
;; an ordinary LIST, so the caller keeps its own pairing of parallel sequences
;; (a source and a target list) instead of a collate protocol.

(defun torch::%b-elements (s)
  ;; One sequence of a batch as a LIST of numbers: a list passes through, a
  ;; tensor or a raw array is read out element-wise.
  (cond ((consp s) s)
        ((null s) nil)
        ((torch:tensorp s) (torch::%b-elements (aref s 1)))
        ((and (arrayp s) (not (stringp s))) (linalg:to-list s))
        (t (error "torch: expected a list, an array or a tensor"))))

(defun torch:pad-sequence (sequences &key (padding-value 0))
  ;; A list of variable-length sequences (lists, index vectors or tensors) as
  ;; ONE padded rank-2 tensor, BATCH FIRST: (batch longest), every row filled
  ;; up to the longest one with padding-value (torch.nn.utils.rnn.pad_sequence
  ;; with batch_first=True). The result is a constant tensor -- token indices,
  ;; ready for torch:embedding and torch:padding-mask.
  (let* ((rows (mapcar (function torch::%b-elements) sequences))
         (b (length rows))
         (w 0))
    (do ((p rows (cdr p)))
        ((null p))
      (let ((n (length (car p)))) (when (> n w) (setq w n))))
    (let ((out (linalg:full (list b w) (* 1.0 padding-value))))
      (do ((p rows (cdr p)) (i 0 (+ i 1)))
          ((null p))
        (do ((q (car p) (cdr q)) (j 0 (+ j 1)))
            ((null q))
          (setf (aref out i j) (* 1.0 (car q)))))
      (torch::%t-new out nil nil nil nil))))

(defun torch::%b-index-list (n)
  ;; The integers 0..n-1 as a list.
  (let ((acc nil))
    (do ((i (- n 1) (- i 1)))
        ((< i 0) acc)
      (setq acc (cons i acc)))))

(defun torch::%b-shuffle (items n)
  ;; items reordered by a draw of the SEEDED linalg generator
  ;; (linalg:permutation, Fisher-Yates), so linalg:seed reproduces the epoch on
  ;; every backend.
  (let ((v (make-array n :initial-element nil))
        (perm (linalg:permutation n))
        (acc nil))
    (do ((p items (cdr p)) (i 0 (+ i 1)))
        ((null p))
      (setf (aref v i) (car p)))
    (do ((i (- n 1) (- i 1)))
        ((< i 0) acc)
      (setq acc (cons (aref v (truncate (aref perm i))) acc)))))

(defun torch:shuffled-batches (data batch-size &key (shuffle t) drop-last)
  ;; data cut into mini-batches: a list of LISTS, each of batch-size elements
  ;; except possibly the last (dropped under :drop-last t, like a DataLoader's
  ;; drop_last). data is a LIST of examples, or a non-negative INTEGER n
  ;; standing for the index list 0..n-1 -- the spelling that batches several
  ;; parallel arrays at once, since the caller can select the same rows out of
  ;; each. The order comes from the seeded linalg generator, so linalg:seed
  ;; reproduces the epoch on every backend; :shuffle nil keeps data's own order
  ;; (an evaluation pass uses the same function).
  (let* ((items (if (numberp data) (torch::%b-index-list data) data))
         (n (length items))
         (order (if shuffle (torch::%b-shuffle items n) items))
         (batches nil)
         (cur nil)
         (k 0))
    (do ((p order (cdr p)))
        ((null p))
      (setq cur (cons (car p) cur))
      (setq k (+ k 1))
      (when (>= k batch-size)
        (setq batches (cons (reverse cur) batches))
        (setq cur nil)
        (setq k 0)))
    (when (and cur (null drop-last))
      (setq batches (cons (reverse cur) batches)))
    (reverse batches)))

(defun torch:padding-mask (tokens &key (pad-id 0))
  ;; The padding mask of a (batch length) token matrix: 1.0 at every position
  ;; holding pad-id and 0.0 elsewhere, with a query axis inserted --
  ;; (batch 1 length) -- so it broadcasts over an attention score's
  ;; (batch query-length key-length). A RAW linalg array, not a tensor: a mask
  ;; is a constant, and torch:masked-fill takes it as one.
  (linalg:expand-dims (linalg:equal (torch::%t-indices tokens) pad-id) 1))

(defun torch:subsequent-mask (sequence-length)
  ;; The causal (look-ahead) mask of a sequence: 1.0 strictly ABOVE the
  ;; diagonal, shaped (1 sequence-length sequence-length) so it broadcasts over
  ;; the batch -- position i may not attend to any j > i. A RAW linalg array,
  ;; like torch:padding-mask; the two combine with linalg:add or
  ;; linalg:maximum, since torch:masked-fill treats every non-zero as masked.
  (linalg:expand-dims
   (linalg:triu (linalg:ones (list sequence-length sequence-length)) :k 1) 0))
