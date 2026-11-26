# torch:topk

`(torch:topk a k &key axis indices)`

The `k` largest elements along `axis` (`-1`, the last axis, by default),
ORDERED LARGEST FIRST, as a RAW linalg array shaped like `a` with that axis
narrowed to `k`. Non-differentiable, like [`torch:argmax`](torch-argmax.md).

PyTorch's `torch.topk` returns the values and their indices as a pair; this
returns ONE of them -- the values, or under `:indices t` the positions they came
from -- because every function in this package is single-valued. Ties keep the
LOWEST index, so a run is reproducible on every backend, where `torch.topk`'s
tie order is not specified at all.

The top-`k` step of a sampling loop is this plus
[`torch:masked-fill`](torch-masked-fill.md): everything below the row's `k`-th
largest logit becomes `-infinity`, so the softmax gives it weight exactly `0`.

```lisp
(torch:topk (linalg:from-list '((1.0 5.0 3.0) (9.0 2.0 8.0))) 2)
; => #d((5.0 3.0) (9.0 8.0))
(torch:topk (linalg:from-list '((1.0 5.0 3.0) (9.0 2.0 8.0))) 2 :indices t)
; => #d((1.0 2.0) (0.0 2.0))
```
