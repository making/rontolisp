# linalg:argmin

`(linalg:argmin vector)`

Returns the zero-based index of the smallest element of a vector, taking the first index on ties. It accepts vectors only; an empty vector signals an error. For the value itself (and matrix support), use [`linalg:amin`](linalg-amin.md); the counterpart is [`linalg:argmax`](linalg-argmax.md).

```lisp
(linalg:argmin #(5 2 8)) ; => 1
```
