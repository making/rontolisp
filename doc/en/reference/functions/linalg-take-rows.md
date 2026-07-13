# linalg:take-rows

`(linalg:take-rows array indices)`

Returns the axis-0 slices of `array` selected by the index vector `indices` (numpy's `x[mask]` / `np.take(a, idx, axis=0)`), as a fresh array of the input's width. Whole slabs are copied row-major, so any rank >= 1 works; index values are truncated to integers and the same index may appear more than once. With [`linalg:choice`](linalg-choice.md) or [`linalg:permutation`](linalg-permutation.md) supplying the indices this is the mini-batch extraction idiom; the per-row element pick is [`linalg:gather`](linalg-gather.md). Axis 0 survives even for a single index (`#(2)` yields a `(1 n)` matrix, numpy's `x[[2]]`); to take one slice and drop the axis, use [`linalg:row`](linalg-row.md).

```lisp
(linalg:take-rows #2A((1 2 3) (4 5 6) (7 8 9)) #(2 0)) ; => #d((7.0 8.0 9.0) (1.0 2.0 3.0))
```
