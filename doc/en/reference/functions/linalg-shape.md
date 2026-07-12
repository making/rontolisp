# linalg:shape

`(linalg:shape array)`

Returns the dimension sizes of an array as a list: `(n)` for a rank-1 vector, `(rows cols)` for a rank-2 matrix. It is the linalg spelling of `array-dimensions`. For the number of dimensions, use [`linalg:ndim`](linalg-ndim.md); for the total element count, [`linalg:size`](linalg-size.md).

```lisp
(linalg:shape #2A((1 2 3) (4 5 6))) ; => (2 3)
(linalg:shape #(1 2 3))             ; => (3)
```
