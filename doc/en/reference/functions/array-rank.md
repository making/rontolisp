# array-rank

`(array-rank array)`

Returns the number of dimensions of `array`: 0 for a rank-0 array (`(make-array nil)`), 1 for a vector, 2 for a two-dimensional array, and so on for higher ranks. It equals the length of the list returned by [`array-dimensions`](array-dimensions.md).

A string is a rank-1 array of characters, so its rank is 1.

```lisp
(array-rank (vector 1)) ; => 1
(array-rank (make-array '(2 3))) ; => 2
(array-rank (make-array nil)) ; => 0
(array-rank "abc") ; => 1
```
