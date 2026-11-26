# torch:masked-fill

`(torch:masked-fill a mask value)`

Differentiable masked fill (`torch.masked_fill` over `linalg:where`): the scalar `value` where `mask` is non-zero, `a`'s element where it is zero. `mask` (a 0/1 array, a comparison mask, or a tensor) and `value` are constants -- no gradient flows to them; `a`'s gradient is zero at the filled positions. Filling attention scores with `-infinity` before [`torch:softmax`](torch-softmax.md) is the masked-attention idiom. With a number as `value` and a mask that broadcasts into `a`'s shape, the result is a **view**: nothing is computed until something reads the data, and a `torch:softmax` over it folds the fill into its own pass.

```lisp
(torch:data (torch:masked-fill (torch:tensor '((1.0 2.0) (3.0 4.0)))
                               #2A((0 1) (0 0)) -1.0))
; => #f((1.0 -1.0) (3.0 4.0))
```
