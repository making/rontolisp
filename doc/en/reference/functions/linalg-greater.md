# linalg:greater

`(linalg:greater a b)`

Returns the elementwise `a > b` comparison as a `0.0`/`1.0` mask (numpy's `>`, which produces a boolean array); either operand may be a scalar, and arrays broadcast by numpy's rules exactly as [`linalg:add`](linalg-add.md) describes. Multiply by the mask where numpy would boolean-index -- `(linalg:greater x 0)` is the relu-gradient mask, and a thresholded [`linalg:rand`](linalg-rand.md) gives a dropout mask. The siblings are [`linalg:greater-equal`](linalg-greater-equal.md), [`linalg:less`](linalg-less.md), [`linalg:less-equal`](linalg-less-equal.md) and [`linalg:equal`](linalg-equal.md).

```lisp
(linalg:greater #(1 5 3) 2) ; => #d(0.0 1.0 1.0)
```
