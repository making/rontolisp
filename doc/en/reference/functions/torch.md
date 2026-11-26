# torch Package Functions

The `torch` package is the PyTorch-style differentiable layer over `linalg`
(see the [Neural Networks guide](../../guides/neural-networks.md)): a tensor that
records how it was computed, and a `torch:backward` that walks that history to
fill in gradients. It is **not part of Common Lisp**; reference its functions
with the `torch:` qualifier (the package does not use `cl`). Every operation
accepts tensors, numbers, arrays or lists as operands, computes through the
`linalg` kernels (so `--simd` accelerates torch programs for free), and a
tensor prints as `#<TENSOR data>` -- read the values back with `torch:data` /
`torch:item` / `torch:grad`. The middle of the table is the `nn`-style module
layer: a module owns its parameters in a fields plist, composes, and is run with
`torch:forward`. The last part is what turns a model into a training run: the
optimizers, whose `torch:step` updates every parameter in place, and the
batching / padding / mask helpers, which are plain functions rather than a
`Dataset`/`DataLoader` hierarchy. The one macro, `torch:no-grad`, is on the
[Macros page](../macros/torch-no-grad.md).

| Function | Example | Result |
|----------|---------|--------|
| `torch:tensor` | `(torch:tensor '(1 2) :requires-grad t)` | a leaf tensor over packed data (`:element-type 'single-float` for `#f`) |
| `torch:tensorp` | `(torch:tensorp x)` | `T` for a tensor, `NIL` otherwise |
| `torch:data` | `(torch:data tn)` | the linalg array (or number, for a scalar tensor) |
| `torch:grad` | `(torch:grad tn)` | the accumulated gradient, or `NIL` before backward |
| `torch:shape` | `(torch:shape tn)` | the dims list; `NIL` for a scalar tensor |
| `torch:item` | `(torch:item tn)` | the number in a one-element tensor |
| `torch:detach` | `(torch:detach tn)` | a leaf sharing the data, cut off from the tape |
| `torch:zero-grad` | `(torch:zero-grad tn)` | clears the gradient slot; returns the tensor |
| `torch:requires-grad-p` | `(torch:requires-grad-p tn)` | whether the tensor participates in autograd |
| `torch:backward` | `(torch:backward loss)` | reverse-mode autograd from a scalar tensor (accumulates into `torch:grad`) |
| `torch:add` | `(torch:add a b)` | differentiable elementwise `+` with broadcasting |
| `torch:sub` | `(torch:sub a b)` | differentiable elementwise `-` |
| `torch:mul` | `(torch:mul a b)` | differentiable elementwise (Hadamard) `*` |
| `torch:div` | `(torch:div a b)` | differentiable elementwise `/` |
| `torch:neg` | `(torch:neg a)` | differentiable negation |
| `torch:power` | `(torch:power a 2)` | differentiable elementwise `a ** b` |
| `torch:exp` | `(torch:exp a)` | differentiable `e^x` |
| `torch:log` | `(torch:log a)` | differentiable natural log |
| `torch:sqrt` | `(torch:sqrt a)` | differentiable square root |
| `torch:tanh` | `(torch:tanh a)` | differentiable hyperbolic tangent |
| `torch:relu` | `(torch:relu a)` | differentiable `max(x, 0.0)` |
| `torch:erf` | `(torch:erf a)` | differentiable Gauss error function |
| `torch:gelu` | `(torch:gelu a)` | differentiable GELU (`:approximate :none` / `:tanh`) |
| `torch:matmul` | `(torch:matmul a b)` | differentiable matrix product (batched at rank >= 3) |
| `torch:sum` | `(torch:sum a :axis 0)` | differentiable sum (whole tensor or along an axis) |
| `torch:mean` | `(torch:mean a)` | differentiable mean |
| `torch:var` | `(torch:var a :ddof 1)` | differentiable variance (`(n - ddof)` divisor) |
| `torch:std` | `(torch:std a)` | differentiable standard deviation |
| `torch:amax` | `(torch:amax a :axis 0)` | differentiable maximum (gradient split among ties) |
| `torch:argmax` | `(torch:argmax a)` | index of the largest element (non-differentiable, raw value) |
| `torch:topk` | `(torch:topk a 5)` | the `k` largest values along an axis, largest first (`:indices t` for their positions) |
| `torch:multinomial` | `(torch:multinomial probs)` | indices drawn per row from the seeded generator (`:num-samples`, `:replacement`) |
| `torch:softmax` | `(torch:softmax a :axis 1)` | differentiable max-subtracted softmax |
| `torch:log-softmax` | `(torch:log-softmax a :axis 1)` | differentiable log-softmax (cross-entropy half) |
| `torch:masked-fill` | `(torch:masked-fill a mask v)` | differentiable fill of `v` where the mask is non-zero |
| `torch:gather` | `(torch:gather a idx)` | differentiable per-row element pick of a matrix |
| `torch:index-select` | `(torch:index-select a idx)` | differentiable row selection (the embedding lookup; repeats accumulate) |
| `torch:reshape` | `(torch:reshape a '(2 3))` | differentiable row-major reshape |
| `torch:view` | `(torch:view a '(2 3))` | `torch:reshape` under PyTorch's other name |
| `torch:transpose` | `(torch:transpose a '(1 0 2))` | differentiable transpose / axes permutation |
| `torch:unsqueeze` | `(torch:unsqueeze a 0)` | differentiable extent-1 axis insertion |
| `torch:squeeze` | `(torch:squeeze a)` | differentiable extent-1 axis removal |
| `torch:cat` | `(torch:cat (list a b) :axis 1)` | differentiable concatenation along an existing axis |
| `torch:stack` | `(torch:stack (list a b))` | differentiable join along a new axis |
| `torch:slice` | `(torch:slice a '(nil (0 2)))` | differentiable numpy basic slicing |
| `torch:set-data` | `(torch:set-data tn v)` | replaces a tensor's data in place (the parameter update) |
| `torch:module` | `(torch:module :k fields fn)` | a user layer: a kind, a fields plist and a forward function |
| `torch:modulep` | `(torch:modulep x)` | `T` for a module, `NIL` otherwise |
| `torch:module-kind` | `(torch:module-kind m)` | the module's kind keyword |
| `torch:field` | `(torch:field m :weight)` | the value of a module's named field (signals when absent) |
| `torch:fields` | `(torch:fields m)` | the whole fields plist, as a fresh list -- the module walk |
| `torch:set-field` | `(torch:set-field m :weight p)` | sets a module's named field; returns the module |
| `torch:forward` | `(torch:forward m x)` | runs a module's (or a plain function's) forward pass |
| `torch:parameter` | `(torch:parameter '(1.0))` | a leaf tensor with `requires-grad` -- a trainable parameter |
| `torch:parameters` | `(torch:parameters m)` | every parameter reachable from a module, deduplicated |
| `torch:train` | `(torch:train m)` | puts the module and its submodules into training mode |
| `torch:eval` | `(torch:eval m)` | puts the module and its submodules into evaluation mode |
| `torch:training-p` | `(torch:training-p m)` | whether the module is in training mode |
| `torch:linear` | `(torch:linear 4 8)` | a fully connected layer (`:weight`, `:bias`) |
| `torch:embedding` | `(torch:embedding 100 8)` | an embedding table (`:weight`), indices of any shape |
| `torch:sequential` | `(torch:sequential a #'torch:relu b)` | a chain of layers and/or plain functions |
| `torch:layer-norm` | `(torch:layer-norm 8)` | layer normalization over the last axis (`ddof` 0) |
| `torch:dropout` | `(torch:dropout 0.1)` | inverted dropout; the identity in evaluation mode |
| `torch:mse-loss` | `(torch:mse-loss y target)` | mean squared error (`:reduction :mean` / `:sum` / `:none`) |
| `torch:cross-entropy-loss` | `(torch:cross-entropy-loss logits target)` | cross entropy over logits; target is class indices (`:ignore-index` skips padding) or a probability distribution |
| `torch:optimizer` | `(torch:optimizer :k ps fields fn)` | a user optimizer: a kind, parameters, a fields plist and a step function |
| `torch:optimizerp` | `(torch:optimizerp x)` | `T` for an optimizer, `NIL` otherwise |
| `torch:optimizer-kind` | `(torch:optimizer-kind o)` | the optimizer's kind keyword |
| `torch:optimizer-params` | `(torch:optimizer-params o)` | the parameter tensors the optimizer updates |
| `torch:step` | `(torch:step o)` | applies the update rule to every parameter (in place, off the tape) |
| `torch:step-count` | `(torch:step-count o)` | how many times `torch:step` has run (Adam's `t`) |
| `torch:sgd` | `(torch:sgd model :lr 0.1)` | SGD, optionally with `:momentum` / `:weight-decay` |
| `torch:adam` | `(torch:adam model :lr 0.001)` | Adam (`:betas`, `:eps`, `:weight-decay`), bias-corrected from the first step |
| `torch:adamw` | `(torch:adamw model :lr 0.001)` | AdamW: the same rule with DECOUPLED `:weight-decay` (default `0.01`) |
| `torch:clip-grad-norm` | `(torch:clip-grad-norm model 1.0)` | scales every gradient in place when their total L2 norm exceeds the bound; returns that norm |
| `torch:pad-sequence` | `(torch:pad-sequence seqs)` | variable-length sequences as one padded batch-first tensor |
| `torch:shuffled-batches` | `(torch:shuffled-batches n 32)` | mini-batches from the seeded generator (`:shuffle`, `:drop-last`) |
| `torch:padding-mask` | `(torch:padding-mask tokens)` | `(batch 1 length)` mask of the padding positions (a raw array) |
| `torch:subsequent-mask` | `(torch:subsequent-mask 8)` | `(1 n n)` causal mask, `1.0` above the diagonal (a raw array) |

