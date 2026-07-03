# linalg:argmax

`(linalg:argmax vector)`

Returns the zero-based index of the largest element of a vector, taking the first index on ties. It accepts vectors only; an empty vector signals an error. For the value itself (and matrix support), use [`linalg:amax`](linalg-amax.md); the counterpart is [`linalg:argmin`](linalg-argmin.md).

```lisp
(linalg:argmax #(1 9 3)) ; => 1
```
