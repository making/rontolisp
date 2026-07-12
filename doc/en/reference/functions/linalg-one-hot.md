# linalg:one-hot

`(linalg:one-hot indices n &optional element-type)`

Returns the `(length indices)` x `n` one-hot matrix: row `i` holds `1.0` in column `indices[i]` (truncated to an integer) and `0.0` elsewhere -- the label-encoding step for a classification loss. Double by default; pass `'single-float` for a packed `#f` result. [`linalg:gather`](linalg-gather.md) goes the other way, picking one element per row by index.

```lisp
(linalg:one-hot #(1 0 2) 3) ; => #d((0.0 1.0 0.0) (1.0 0.0 0.0) (0.0 0.0 1.0))
```
