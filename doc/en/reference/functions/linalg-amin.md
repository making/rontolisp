# linalg:amin

`(linalg:amin array &optional axis keepdims)`

Returns the smallest element of a vector or matrix. An empty array signals an error. For the *index* of the smallest element, use [`linalg:argmin`](linalg-argmin.md); the counterpart for the largest element is [`linalg:amax`](linalg-amax.md).

With an integer `axis` (negative counts from the end) it reduces along that axis instead, following the axis and `keepdims` rules of [`linalg:sum`](linalg-sum.md). The reduction is a strict-comparison fold: the first element wins ties and a `NaN` never replaces the seed. An empty array or axis signals an error.

```lisp
(linalg:amin #(5 2 8)) ; => 2
(linalg:amin #2A((1 9) (3 4)) 0) ; => #d(1.0 4.0)
```
