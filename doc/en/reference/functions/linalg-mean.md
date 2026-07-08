# linalg:mean

`(linalg:mean array)`

Returns the arithmetic mean of every element: the [`linalg:sum`](linalg-sum.md) divided by the [`linalg:size`](linalg-size.md). Like a reduction in numpy, the result follows the element type: a packed double-float array (anything built by a linalg constructor) gives a double, while a plain integer array gives an exact rational.

```lisp
(linalg:mean #(1 2 3 4)) ; => 5/2
```
