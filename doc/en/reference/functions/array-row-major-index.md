# array-row-major-index

`(array-row-major-index array &rest subscripts)`

Returns the 0-based flat row-major index of the element of `array` at the given subscripts (one per dimension): the fold `((s0 * d1 + s1) * d2 + s2) ...` over the dimension sizes. A rank-0 array takes no subscripts, and its empty fold is 0. The result is a valid index for [`row-major-aref`](row-major-aref.md).

```lisp
(array-row-major-index (make-array (list 2 3)) 1 1) ; => 4
(array-row-major-index (make-array nil)) ; => 0
```
