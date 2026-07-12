# linalg:argmax

`(linalg:argmax array &optional axis)`

Returns the zero-based index of the largest element of a vector, taking the first index on ties. Without an axis it accepts vectors only; an empty vector signals an error. For the value itself, use [`linalg:amax`](linalg-amax.md); the counterpart is [`linalg:argmin`](linalg-argmin.md).

With an integer `axis` (negative counts from the end) it returns the per-slice indices along that axis, the axis dropped from the result -- the classification idiom `(linalg:argmax logits 1)`. A rank >= 2 result is a packed *double* array of index values (linalg arrays have no integer width, and `(= 3.0 3)` holds for comparisons); a vector reduces to the integer index itself.

```lisp
(linalg:argmax #(1 9 3)) ; => 1
(linalg:argmax #2A((1 9 3) (7 5 6)) 1) ; => #d(1.0 0.0)
```
