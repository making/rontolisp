# linalg:amax

`(linalg:amax array &optional axis keepdims)`

Returns the largest element of a vector or matrix. An empty array signals an error. For the *index* of the largest element, use [`linalg:argmax`](linalg-argmax.md); the counterpart for the smallest element is [`linalg:amin`](linalg-amin.md).

With an integer `axis` (negative counts from the end) it reduces along that axis instead, following the axis and `keepdims` rules of [`linalg:sum`](linalg-sum.md). The reduction is a strict-comparison fold: the first element wins ties and a `NaN` never replaces the seed. An empty array or axis signals an error.

```lisp
(linalg:amax #2A((1 9) (3 4))) ; => 9
(linalg:amax #2A((1 9) (3 4)) 0) ; => #d(3.0 9.0)
(linalg:amax #2A((1 9) (3 4)) 1 t) ; => #d((9.0) (4.0))
```
