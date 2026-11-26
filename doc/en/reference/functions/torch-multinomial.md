# torch:multinomial

`(torch:multinomial probs &key num-samples replacement)`

`num-samples` indices drawn from each row of `probs`, whose LAST axis holds the
weights (PyTorch's `torch.multinomial`). The weights need not sum to `1` -- each
row is normalized -- and must be non-negative. A rank-1 input answers a
`(num-samples)` index array, a rank-n input its own shape with the last axis
replaced by `num-samples`. Non-differentiable, and a RAW linalg array rather
than a tensor.

Without `:replacement t` an index already drawn cannot be drawn again within the
same row, which is PyTorch's default; `num-samples` must then not exceed the
number of weights. The draw comes from the SEEDED
[`linalg:seed`](linalg-seed.md) generator, so a sampling run reproduces on every
backend.

```lisp
(linalg:seed 3)
(torch:multinomial (linalg:from-list '((0.0 1.0 0.0) (0.0 0.0 1.0))))
; => #d((1.0) (2.0))
```
