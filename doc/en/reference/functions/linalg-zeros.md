# linalg:zeros

`(linalg:zeros shape)`

Creates a zero-filled array. `shape` is an integer for a rank-1 vector of that length, or a list of two integers `(rows cols)` for a rank-2 matrix -- the same shape convention used by [`linalg:ones`](linalg-ones.md) and [`linalg:full`](linalg-full.md). See the [linalg guide](../../guides/linear-algebra.md) for an overview of the package.

```lisp
(linalg:zeros 3)      ; => #d(0.0 0.0 0.0)
(linalg:zeros '(2 2)) ; => #d((0.0 0.0) (0.0 0.0))
```
