# torch:dropout

`(torch:dropout p)`

Returns a dropout layer (PyTorch's `nn.Dropout`) with drop probability p, in the single field `:p`. In **training** mode it zeroes each element with probability p and scales the survivors by `1 / (1 - p)` (inverted dropout, so the expectation is unchanged); in **evaluation** mode ([`torch:eval`](torch-eval.md)) it is the identity, and so is `p` 0. The mask comes from the seeded [`linalg:seed`](linalg-seed.md) generator, so a seeded training run reproduces on every backend.

```lisp
(defparameter *drop* (torch:dropout 0.5))
(torch:data (torch:forward (torch:eval *drop*) (torch:tensor '(1.0 2.0)))) ; => #f(1.0 2.0)
(torch:data (torch:forward (torch:dropout 0) (torch:tensor '(1.0 2.0))))   ; => #f(1.0 2.0)
```
