# linalg:ones

`(linalg:ones shape &key element-type)`

Creates an array with every element set to 1. `shape` is an integer for a rank-1 vector or a list `(rows cols)` for a rank-2 matrix, like [`linalg:zeros`](linalg-zeros.md). For an arbitrary fill value use [`linalg:full`](linalg-full.md). Double-float by default; pass `:element-type 'single-float` for a packed single-float (`#f`) result.

```lisp
(linalg:ones '(2 2)) ; => #d((1.0 1.0) (1.0 1.0))
(linalg:ones 2 :element-type 'single-float) ; => #f(1.0 1.0)
```
