# linalg:solve

`(linalg:solve a b)`

Solves the linear system `a . x = b` for `x`, where `a` is a square matrix and `b` is a vector (giving a vector solution) or a matrix (giving a matrix solution, one column system at a time). Integer and rational inputs give an exact rational result. A singular `a` signals an error, since the implementation applies [`linalg:inv`](linalg-inv.md) via [`linalg:dot`](linalg-dot.md).

```lisp
(linalg:solve (linalg:from-list '((2 1) (1 3)))
              (linalg:from-list '(3 5))) ; => #(4/5 7/5)
```
