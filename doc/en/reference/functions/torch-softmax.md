# torch:softmax

`(torch:softmax a &key axis)`

Differentiable max-subtracted softmax (`linalg:softmax`): with no `:axis` the whole tensor is one distribution, with an integer `:axis` one distribution per slice -- torch's `softmax(x, dim)`, the attention-weight form. The backward pass is `s * (g - sum(g * s))` over each distribution. Masked positions filled with `-infinity` by [`torch:masked-fill`](torch-masked-fill.md) come out as exactly `0.0`. Over a score that was divided by a scalar and masked -- `(torch:softmax (torch:masked-fill (torch:div score s) mask -inf) :axis -1)`, the attention head's idiom -- the `:axis` form runs the three as **one node**: the division and the fill are views, and forward and backward go through the score once, with the same bits as the three separate nodes.

```lisp
(torch:data (torch:softmax (torch:tensor '(1.0 1.0 1.0 1.0))))          ; => #f(0.25 0.25 0.25 0.25)
(torch:data (torch:softmax (torch:tensor '((0.0 0.0) (1.0 1.0))) :axis 1)) ; => #f((0.5 0.5) (0.5 0.5))
```
