# linalg:argmin

`(linalg:argmin array &key axis)`

Returns the zero-based index of the smallest element of a vector, taking the first index on ties. Without an axis it accepts vectors only; an empty vector signals an error. For the value itself, use [`linalg:amin`](linalg-amin.md); the counterpart is [`linalg:argmax`](linalg-argmax.md).

With an integer `:axis` (negative counts from the end) it returns the per-slice indices along that axis, the axis dropped from the result. A rank >= 2 result is a packed *double* array of index values (linalg arrays have no integer width, and `(= 3.0 3)` holds for comparisons); a vector reduces to the integer index itself.

```lisp
(linalg:argmin #(5 2 8)) ; => 1
(linalg:argmin #2A((1 9 3) (7 5 6)) :axis 1) ; => #d(0.0 1.0)
```
