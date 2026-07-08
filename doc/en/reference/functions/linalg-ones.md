# linalg:ones

`(linalg:ones shape)`

Creates an array with every element set to 1. `shape` is an integer for a rank-1 vector or a list `(rows cols)` for a rank-2 matrix, like [`linalg:zeros`](linalg-zeros.md). For an arbitrary fill value use [`linalg:full`](linalg-full.md).

```lisp
(linalg:ones '(2 2)) ; => #2A((1.0 1.0) (1.0 1.0))
```
