# array-total-size

`(array-total-size array)`

Returns the total number of elements in `array` -- the product of its dimension sizes over every dimension. For a rank-1 vector this is simply its length; for a rank-2 array it is rows times columns; for a rank-0 array it is 1 (the empty product), since such an array holds exactly one element. See also [`array-dimensions`](array-dimensions.md).

A string is a rank-1 array of characters, so its total size is its capacity — for a character vector with a fill pointer, more than its [`length`](length.md).

```lisp
(array-total-size (vector 1 2 3)) ; => 3
(array-total-size (make-array '(2 3))) ; => 6
(array-total-size (make-array nil)) ; => 1
(array-total-size "abc") ; => 3
(array-total-size (make-array 5 :element-type 'character :fill-pointer 2)) ; => 5
```
