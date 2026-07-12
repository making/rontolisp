# linalg:mean

`(linalg:mean array &optional axis keepdims)`

Returns the arithmetic mean of every element: the [`linalg:sum`](linalg-sum.md) divided by the [`linalg:size`](linalg-size.md). Like a reduction in numpy, the result follows the element type: a packed double-float array (anything built by a linalg constructor) gives a double, while a plain integer array gives an exact rational.

With an integer `axis` (negative counts from the end) it averages along that axis instead, following exactly the axis and `keepdims` rules of [`linalg:sum`](linalg-sum.md): the axis is dropped from the result (kept with extent 1 under a non-nil `keepdims`), and a vector without `keepdims` reduces to the scalar itself.

```lisp
(linalg:mean #(1 2 3 4)) ; => 5/2
(linalg:mean #2A((1 2 3) (4 5 6)) 0) ; => #d(2.5 3.5 4.5)
```
