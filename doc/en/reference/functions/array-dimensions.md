# array-dimensions

`(array-dimensions array)`

Returns a list of the sizes of each dimension of `array`, at any rank: a rank-1 vector yields a one-element list, a rank-2 array a two-element list, and a rank-0 array the empty list (`nil`). See [`array-dimension`](array-dimension.md) for the size of a single axis and [`array-rank`](array-rank.md) for the number of dimensions.

```lisp
(array-dimensions (make-array '(2 3))) ; => (2 3)
(array-dimensions (vector 1 2)) ; => (2)
(array-dimensions (make-array nil)) ; => NIL
```
