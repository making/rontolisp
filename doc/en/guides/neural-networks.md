# Neural Networks (torch)

The `torch` package is a PyTorch-style layer over [linalg](linear-algebra.md): a **tensor** that remembers how it was computed, and a `torch:backward` that walks that history in reverse to fill in gradients. Everything a hand-written backpropagation pass used to do -- tracking which arrays fed which, deriving each operation's adjoint, summing gradients over broadcast axes -- happens automatically, one operation at a time.

The package is implemented once in Lisp source and behaves identically on every backend. Every operation computes through the `linalg` kernels, so a torch program is accelerated under [`--simd`](simd-acceleration.md) for free, and the numerical results are the linalg results.

## Tensors

`torch:tensor` builds a leaf tensor from a number, a list, an array or a linalg array. `:requires-grad t` marks a parameter -- a tensor whose gradient the backward pass should fill in. A tensor prints as its raw record, so read values back with `torch:data` (the array), `torch:item` (the number inside a one-element tensor) and `torch:shape`:

```lisp
(defparameter *w* (torch:tensor '(1.0 2.0) :requires-grad t))
(torch:data *w*)             ; => #d(1.0 2.0)
(torch:shape *w*)            ; => (2)
(torch:requires-grad-p *w*)  ; => T
(torch:item (torch:tensor 2.5)) ; => 2.5
```

Operations accept tensors, numbers, raw arrays and lists interchangeably; non-tensors become constants that no gradient flows to:

```lisp
(torch:data (torch:add *w* 10))                        ; => #d(11.0 12.0)
(torch:data (torch:matmul #2A((1.0 2.0) (3.0 4.0)) *w*)) ; => #d(5.0 11.0)
```

## Recording and the backward pass

An operation whose operand participates in autograd records the operation on a tape. `torch:backward` on a scalar (one-element) tensor seeds its gradient with `1.0`, visits the recorded operations in reverse topological order, and accumulates each input's gradient -- so a tensor used twice (a residual connection, a reused embedding row) collects the **sum** of both paths. Read the result with `torch:grad`:

```lisp
(defparameter *loss* (torch:sum (torch:mul *w* *w*)))
(torch:item *loss*)  ; => 5.0
(torch:backward *loss*)
(torch:grad *w*)     ; => #d(2.0 4.0)
```

Gradients accumulate across backward calls (`+=`), which is what a mini-batch loop wants; `torch:zero-grad` clears a tensor's slot between steps:

```lisp
(torch:backward (torch:sum (torch:mul *w* 3.0)))
(torch:grad *w*)                    ; => #d(5.0 7.0)
(torch:grad (torch:zero-grad *w*))  ; => NIL
```

## Broadcasting and gradients

Elementwise operations broadcast like numpy, and the backward pass reduces each gradient back to its operand's shape by summing over the broadcast axes. A `(d)` bias added to an `(n d)` activation therefore gets a `(d)` gradient -- the sum over the batch axis:

```lisp
(defparameter *b* (torch:tensor '(0.5 0.5) :requires-grad t))
(defparameter *y* (torch:add (torch:tensor '((1.0 2.0) (3.0 4.0))) *b*))
(torch:backward (torch:sum *y*))
(torch:grad *b*) ; => #d(2.0 2.0)
```

## Staying off the tape

`torch:no-grad` runs its body with recording disabled -- the values are computed, nothing is remembered. This is how a training loop's parameter update (and inference in general) stays off the tape. `torch:detach` is the per-tensor spelling: a leaf sharing the same data, cut off from its history:

```lisp
(torch:no-grad
  (torch:requires-grad-p (torch:mul *w* 2.0))) ; => NIL
(torch:requires-grad-p (torch:detach (torch:mul *w* 2.0))) ; => NIL
(torch:requires-grad-p (torch:mul *w* 2.0))    ; => T
```

## A training loop: fit y = 2x

Gradient descent needs nothing beyond what is above: a forward pass building the loss, `torch:backward`, and an update inside `torch:no-grad`. Fitting `y = 2x` by minimizing the mean squared error (the values are chosen so every quantity is an exact dyadic rational -- the printed result is identical on every backend):

```lisp
(defparameter *wf* (torch:tensor '(0.0) :requires-grad t))
(defparameter *x* (torch:tensor '(1.0 2.0)))
(defparameter *t* (torch:tensor '(2.0 4.0)))
(dotimes (i 10)
  (let* ((diff (torch:sub (torch:mul *x* *wf*) *t*))
         (loss (torch:mean (torch:mul diff diff))))
    (torch:backward loss)
    (torch:no-grad
      (setq *wf* (torch:tensor (linalg:sub (torch:data *wf*)
                                           (linalg:mul 0.125 (torch:grad *wf*)))
                               :requires-grad t)))))
(torch:data *wf*) ; => #d(1.999890012666583)
```

## Masked attention scores

`torch:masked-fill` writes a constant where a mask is non-zero; filling with `-infinity` before `torch:softmax` is the masked-attention idiom, and the masked weight comes out as exactly `0.0` -- including through the backward pass:

```lisp
(defparameter *sc* (torch:tensor '((1.0 2.0) (3.0 3.0)) :requires-grad t))
(defparameter *att* (torch:softmax
                     (torch:masked-fill *sc* #2A((0 1) (0 0)) (/ -1.0 0.0))
                     :axis 1))
(torch:data *att*) ; => #d((1.0 0.0) (0.5 0.5))
```

## Packages

`torch` does not use `cl`, so programs stay in `cl-user` and call the qualified names; `#'torch:name` works (every function is a plain defun). The differentiable operations mirror their `linalg` counterparts -- the full list is in the [function reference](../reference/functions.md#torch-package-functions), and `torch:no-grad` on the [macros page](../reference/macros/torch-no-grad.md).
