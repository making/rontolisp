# make-array

`(make-array dimensions &key initial-element fill-pointer adjustable)`

Creates and returns a new array. `dimensions` is an integer for a rank-1 vector, or a non-empty list of integers for an array of any rank. `:initial-element` sets every cell to the given value, defaulting to nil. Elements are stored row-major with O(1) access via `aref`, and arrays are compared by identity (`eq`), so two distinct arrays are never `equal`. `make-array` and `aref` are not first-class function values -- `#'make-array` is unavailable, so call it directly.

`:fill-pointer` (rank-1 only) gives the vector a [fill pointer](fill-pointer.md): an integer sets it to that position, `t` to the vector size. The fill pointer is the effective length -- `length` and printing stop at it, while `aref` still reaches the full storage -- and is what [`vector-push`](vector-push.md)/[`vector-pop`](vector-pop.md)/[`vector-push-extend`](vector-push-extend.md) operate on. `:adjustable` marks the array adjustable, reported verbatim by [`adjustable-array-p`](adjustable-array-p.md). `:element-type` is accepted but ignored (element types are not tracked; [`array-element-type`](array-element-type.md) always returns `t`).

```lisp
(let ((a (make-array 3 :initial-element 0)))
  (aref a 0)) ; => 0
(length (make-array 5 :fill-pointer 2 :initial-element 0)) ; => 2
```
