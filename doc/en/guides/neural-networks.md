# Neural Networks (torch)

The `torch` package is a PyTorch-style layer over [linalg](linear-algebra.md): a **tensor** that remembers how it was computed, and a `torch:backward` that walks that history in reverse to fill in gradients. Everything a hand-written backpropagation pass used to do -- tracking which arrays fed which, deriving each operation's adjoint, summing gradients over broadcast axes -- happens automatically, one operation at a time.

The package is implemented once in Lisp source and behaves identically on every backend. Every operation computes through the `linalg` kernels, so a torch program is accelerated under [`--simd`](simd-acceleration.md) for free, and the numerical results are the linalg results.

## Tensors

`torch:tensor` builds a leaf tensor from a number, a list, an array or a linalg array. `:requires-grad t` marks a parameter -- a tensor whose gradient the backward pass should fill in. A tensor prints as `#<TENSOR data>` (` :REQUIRES-GRAD T` appended for a parameter) -- the same text on every backend, since the printer shows only the data. Read values back with `torch:data` (the array), `torch:item` (the number inside a one-element tensor) and `torch:shape`:

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

## Modules

A **module** owns parameters, composes, and has a forward pass. `torch:module` builds one from a kind keyword, a plist of **fields** and a forward function; `torch:forward` runs it. The fields plist is the parameter registration -- `torch:parameters` walks it -- so a layer's forward reads its parameters back with `torch:field` rather than from a closed-over variable, and a parameter that exists cannot be missing from the walk:

```lisp
(defun scale-layer (n)
  (torch:module :scale (list :gain (torch:parameter (linalg:ones (list n))))
                (lambda (self x) (torch:mul x (torch:field self :gain)))))
(defparameter *scale* (scale-layer 2))
(torch:data (torch:forward *scale* (torch:tensor '(3.0 4.0)))) ; => #d(3.0 4.0)
(length (torch:parameters *scale*))                            ; => 1
```

The walk descends into submodules **and into lists of them**, and deduplicates by identity -- a weight shared by two layers is one parameter, and a list of N blocks needs no `ModuleList` type. A tensor field without `requires-grad` is a buffer and is skipped:

```lisp
(defparameter *stack*
  (torch:module :stack (list :blocks (list *scale* (scale-layer 2))
                             :buffer (torch:tensor '(9.0 9.0)))
                (lambda (self x) x)))
(length (torch:parameters *stack*)) ; => 2
```

`torch:train` and `torch:eval` switch the training flag through the same walk, and `torch:zero-grad` accepts a module -- it clears every parameter's gradient.

## The built-in layers

`torch:linear`, `torch:embedding`, `torch:layer-norm`, `torch:dropout` and `torch:sequential` are ordinary callers of `torch:module`. Their parameters are initialized exactly as PyTorch's are, from the seeded `linalg:seed` generator, so a seeded run reproduces on every backend. Replacing a parameter with `torch:set-field` pins a layer to given weights:

```lisp
(defparameter *lin* (torch:linear 3 2))
(torch:set-field *lin* :weight (torch:parameter '((1.0 0.0) (0.0 1.0) (1.0 1.0))))
(torch:set-field *lin* :bias (torch:parameter '(0.5 -0.5)))
(torch:data (torch:forward *lin* (torch:tensor '((1.0 2.0 3.0))))) ; => #d((4.5 4.5))
```

`torch:sequential` threads its argument through each element, and an element may be a module **or a plain function** -- which is why there is no activation-module type, and why a reshape can sit in a chain:

```lisp
(defparameter *net*
  (torch:sequential (torch:linear 4 8) (function torch:relu) (torch:linear 8 2)))
(torch:shape (torch:forward *net* (torch:tensor (linalg:zeros '(3 4))))) ; => (3 2)
(length (torch:parameters *net*))                                       ; => 4
```

The activations are [`torch:relu`](../reference/functions/torch-relu.md), [`torch:tanh`](../reference/functions/torch-tanh.md) and [`torch:gelu`](../reference/functions/torch-gelu.md), each an ordinary function of a tensor. `torch:gelu` is the exact `x * (1 + erf(x / sqrt(2))) / 2` by default (`nn.GELU`'s own default), over the differentiable [`torch:erf`](../reference/functions/torch-erf.md); `:approximate :tanh` selects the GPT/BERT formulation instead.

[`torch:fields`](../reference/functions/torch-fields.md) answers a module's whole fields plist, which is what makes the tree WALKABLE from outside: `nn.Module.apply` and `nn.Module.named_parameters` have no counterpart here because a walk is written over that plist plus [`torch:module-kind`](../reference/functions/torch-module-kind.md) -- what a layer IS, rather than a substring of a dotted parameter name.

## Losses

`torch:mse-loss` and `torch:cross-entropy-loss` are plain functions returning a scalar tensor. Cross entropy takes raw **logits** (never softmax outputs -- it is computed from `-log-softmax`, the numerically stable form) and flattens all but the last axis, so `(batch seq vocab)` works directly. Its target is either integer class indices, with `:ignore-index` dropping padding positions from both the sum and the mean's denominator, or a full probability distribution of the logits' own shape -- PyTorch's soft-label form, `-sum(target * log-softmax(logits))`:

```lisp
(torch:item (torch:mse-loss (torch:tensor '(1.0 2.0)) '(0.0 0.0))) ; => 2.5
(torch:item (torch:cross-entropy-loss (torch:tensor '((0.0 0.0))) #(0)))
; => 0.6931471805599453
(torch:item (torch:cross-entropy-loss (torch:tensor '((0.0 0.0)))
                                      (torch:tensor '((0.5 0.5)))))
; => 0.6931471805599453
```

A LIST target is always class indices, so the probability spelling needs a tensor or an array; a one-hot distribution and the matching index give the same loss.

## Optimizers

An **optimizer** owns the update rule and its state. `torch:sgd`, `torch:adam` and `torch:adamw` take a model (or a plain list of parameters), keep their hyper-parameters and buffers in a fields plist exactly as a module does, and apply the rule to every parameter when `torch:step` runs:

```lisp
(defparameter *p* (torch:parameter '(1.0 2.0)))
(defparameter *opt* (torch:sgd (list *p*) :lr 0.125 :momentum 0.5))
(torch:backward (torch:sum (torch:mul *p* *p*)))
(torch:step *opt*)
(torch:data *p*)         ; => #d(0.75 1.5)
(torch:step-count *opt*) ; => 1
```

The update writes each parameter's data **in place** and uses no torch operation, so it records nothing on the tape and needs no `torch:no-grad` around it -- unlike a hand-written update built from `torch:set-data`. The state (a momentum buffer, Adam's two moments, the step count its bias correction divides by) lives in the optimizer and never on the parameter, so two optimizers over the same weights keep separate state.

Hyper-parameters are ordinary fields, which is all a learning-rate schedule needs, and `torch:zero-grad` accepts an optimizer as well as a model:

```lisp
(defparameter *adam* (torch:adam (torch:linear 2 2) :lr 0.001))
(torch:field *adam* :lr)                              ; => 0.001
(torch:field (torch:set-field *adam* :lr 0.0005) :lr) ; => 5.0e-4
```

`torch:optimizer` is the constructor all three are built on -- a kind keyword, the parameters, a fields plist and a step function -- so a rule this package does not ship is a plain defun over the same record.

[`torch:adam`](../reference/functions/torch-adam.md) and [`torch:adamw`](../reference/functions/torch-adamw.md) are the SAME rule with the decay in a different place: Adam's `:weight-decay` adds `wd * param` to the gradient, AdamW's shrinks the parameter directly so the adaptive denominator never rescales it. There is no parameter-GROUP object; two optimizers over disjoint parameter lists are what a group is here, which is how a transformer decays its weight matrices and leaves its biases, LayerNorm gains and embedding tables alone.

[`torch:clip-grad-norm`](../reference/functions/torch-clip-grad-norm.md) goes between `torch:backward` and `torch:step`: it returns the total L2 norm of every gradient -- as measured, so the loop can log it -- and scales them in place when that exceeds the bound.

## Training a network

Everything above composes into the loop PyTorch writes: forward, loss, `torch:zero-grad`, `torch:backward`, `torch:step`.

```lisp
(linalg:seed 3)
(defparameter *mlp*
  (torch:sequential (torch:linear 2 8) (function torch:relu) (torch:linear 8 1)))
(defparameter *xs* (torch:tensor '((0.0 0.0) (0.0 1.0) (1.0 0.0) (1.0 1.0))))
(defparameter *ys* (torch:tensor '((0.0) (1.0) (1.0) (0.0))))
(defparameter *sgd* (torch:sgd *mlp* :lr 0.2))
(dotimes (i 200)
  (let ((loss (torch:mse-loss (torch:forward *mlp* *xs*) *ys*)))
    (torch:zero-grad *sgd*)
    (torch:backward loss)
    (torch:step *sgd*)))
(< (torch:item (torch:mse-loss (torch:forward *mlp* *xs*) *ys*)) 1.0e-6) ; => T
```

Writing the update by hand instead needs `torch:no-grad` around it, because `torch:sub` on a parameter would record on the tape; `torch:set-data` then writes the new value into the very tensor the layer's fields point at, so the model keeps using it:

```lisp
(defun sgd-step (model lr)
  (torch:no-grad
    (dolist (p (torch:parameters model))
      (torch:set-data p (linalg:sub (torch:data p)
                                    (linalg:mul lr (torch:grad p)))))))
(sgd-step *mlp* 0.2)
(torch:training-p *mlp*) ; => T
```

## Batching, padding and masks

There is no `Dataset`/`DataLoader` hierarchy: a batch is an ordinary list. `torch:shuffled-batches` cuts a list of examples -- or an integer `n`, standing for the index list `0..n-1`, which is how several parallel arrays get batched at once -- into mini-batches ordered by the seeded generator, so an epoch reproduces on every backend:

```lisp
(linalg:seed 1)
(torch:shuffled-batches 7 3)                       ; => ((6 0 5) (1 4 3) (2))
(torch:shuffled-batches '(a b c d) 2 :shuffle nil) ; => ((A B) (C D))
```

`torch:pad-sequence` turns a batch of variable-length index sequences into one padded rank-2 tensor, batch first, and the two mask constructors build the constants an attention layer fills with `-infinity`:

```lisp
(defparameter *tokens* (torch:pad-sequence '((1 2 3) (4 5))))
(torch:data *tokens*)         ; => #d((1.0 2.0 3.0) (4.0 5.0 0.0))
(torch:padding-mask *tokens*) ; => #d(((0.0 0.0 0.0)) ((0.0 0.0 1.0)))
(torch:subsequent-mask 3)     ; => #d(((0.0 1.0 1.0) (0.0 0.0 1.0) (0.0 0.0 0.0)))
```

Both masks are **raw linalg arrays** -- a mask carries no gradient -- shaped to broadcast over a `(batch query-length key-length)` score: `(batch 1 length)` for the padding mask, `(1 n n)` for the causal one. They combine with `linalg:add`, since `torch:masked-fill` treats every non-zero as masked. The padding value chosen here is also the `:ignore-index` to pass to `torch:cross-entropy-loss`, so the padded positions leave the loss alone.

## Masked attention scores

`torch:masked-fill` writes a constant where a mask is non-zero; filling with `-infinity` before `torch:softmax` is the masked-attention idiom, and the masked weight comes out as exactly `0.0` -- including through the backward pass:

```lisp
(defparameter *sc* (torch:tensor '((1.0 2.0) (3.0 3.0)) :requires-grad t))
(defparameter *att* (torch:softmax
                     (torch:masked-fill *sc* #2A((0 1) (0 0)) (/ -1.0 0.0))
                     :axis 1))
(torch:data *att*) ; => #d((1.0 0.0) (0.5 0.5))
```

## A worked example

[`examples/llm-from-scratch/`](https://github.com/making/rontolisp/blob/develop/examples/llm-from-scratch/README.md) is chapter 2 of 『作ってわかる大規模言語モデルの仕組み』 rewritten on this package: scaled dot-product and multi-head attention, sinusoidal positional encoding, the encoder/decoder Transformer with its padding and causal masks, and a Japanese-English training loop with greedy decoding. Its README carries the PyTorch-to-`torch` mapping table.

## Packages

`torch` does not use `cl`, so programs stay in `cl-user` and call the qualified names; `#'torch:name` works (every function is a plain defun). The differentiable operations mirror their `linalg` counterparts -- the full list is in the [function reference](../reference/functions.md#torch-package-functions), and `torch:no-grad` on the [macros page](../reference/macros/torch-no-grad.md).
