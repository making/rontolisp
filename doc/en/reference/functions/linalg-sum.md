# linalg:sum

`(linalg:sum array)`

Returns the sum of every element of a vector or matrix. Like a reduction in numpy, the result follows the element type: a packed double-float array (anything built by a linalg constructor) gives a double, while a plain integer array gives an integer. For the average, use [`linalg:mean`](linalg-mean.md).

```lisp
(linalg:sum #2A((1 2) (3 4))) ; => 10
```
