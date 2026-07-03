# linalg:norm

`(linalg:norm array)`

Returns the Euclidean (L2) norm of a vector, or the Frobenius norm of a matrix: the square root of the sum of the squared elements. Because `sqrt` returns a float, the result is a float even for integer inputs.

```lisp
(linalg:norm (linalg:from-list '(3 4))) ; => 5.0
```
