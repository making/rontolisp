# linalg:solve

`(linalg:solve a b)`

Solves the linear system `a . x = b` for `x`, where `a` is a square matrix and `b` is a vector (giving a vector solution) or a matrix (giving a matrix solution, one column system at a time). The result is a packed double-float array, since the implementation applies [`linalg:inv`](linalg-inv.md) via [`linalg:dot`](linalg-dot.md) in floating point. A singular `a` signals an error.

```lisp
(linalg:solve #2A((4 0) (2 4)) #(8 8)) ; => #(2.0 1.0)
```
