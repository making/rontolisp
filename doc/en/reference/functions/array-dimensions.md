# array-dimensions

`(array-dimensions array)`

Returns a list of the sizes of each dimension of `array`, at any rank: a rank-1 vector yields a one-element list, a rank-2 array a two-element list, and a rank-0 array the empty list (`nil`). See [`array-dimension`](array-dimension.md) for the size of a single axis and [`array-rank`](array-rank.md) for the number of dimensions.

A string is a rank-1 array of characters, so it answers here too. The size is the array **dimension** — the capacity — which for a character vector with a fill pointer is larger than its [`length`](length.md).

```lisp
(array-dimensions (make-array '(2 3))) ; => (2 3)
(array-dimensions (vector 1 2)) ; => (2)
(array-dimensions (make-array nil)) ; => NIL
(array-dimensions "abc") ; => (3)
(array-dimensions (make-array 5 :element-type 'character :fill-pointer 2)) ; => (5)
```
