# linalg:sum

`(linalg:sum array)`

Returns the sum of every element of a vector or matrix. Integer inputs give an exact integer result. For the average, use [`linalg:mean`](linalg-mean.md).

```lisp
(linalg:sum (linalg:from-list '((1 2) (3 4)))) ; => 10
```
