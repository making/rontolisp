# linalg:flatten

`(linalg:flatten array)`

Returns the elements of an array as a fresh rank-1 vector, in row-major order. It is equivalent to [`linalg:reshape`](linalg-reshape.md) with the total [`linalg:size`](linalg-size.md) as the target shape; a vector input yields a fresh copy of itself.

```lisp
(linalg:flatten (linalg:from-list '((1 2) (3 4)))) ; => #(1 2 3 4)
```
