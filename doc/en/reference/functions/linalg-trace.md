# linalg:trace

`(linalg:trace matrix)`

Returns the trace of a square matrix: the sum of the elements on the main diagonal. A non-square (or rank-1) argument signals an error. See also [`linalg:det`](linalg-det.md) for the other classic square-matrix scalar.

```lisp
(linalg:trace (linalg:from-list '((1 2) (3 4)))) ; => 5
```
