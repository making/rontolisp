# array-total-size

`(array-total-size array)`

Returns the total number of elements in `array` -- the product of its dimension sizes over every dimension. For a rank-1 vector this is simply its length; for a rank-2 array it is rows times columns. See also [`array-dimensions`](array-dimensions.md).

```lisp
(array-total-size (vector 1 2 3)) ; => 3
(array-total-size (make-array '(2 3))) ; => 6
```
