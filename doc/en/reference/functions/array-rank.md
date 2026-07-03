# array-rank

`(array-rank array)`

Returns the number of dimensions of `array`: 1 for a vector, 2 for a two-dimensional array (only ranks 1 and 2 are supported). It equals the length of the list returned by [`array-dimensions`](array-dimensions.md).

```lisp
(array-rank (vector 1)) ; => 1
(array-rank (make-array '(2 3))) ; => 2
```
