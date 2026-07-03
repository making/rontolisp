# linalg:amin

`(linalg:amin array)`

Returns the smallest element of a vector or matrix. An empty array signals an error. For the *index* of the smallest element of a vector, use [`linalg:argmin`](linalg-argmin.md); the counterpart for the largest element is [`linalg:amax`](linalg-amax.md).

```lisp
(linalg:amin #(5 2 8)) ; => 2
```
