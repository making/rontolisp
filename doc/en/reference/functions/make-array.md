# make-array

`(make-array dimensions &key initial-element initial-contents element-type fill-pointer adjustable displaced-to displaced-index-offset)`

Creates and returns a new array. `dimensions` is an integer for a rank-1 vector, or a non-empty list of integers for an array of any rank. `:initial-element` sets every cell to the given value, defaulting to nil. Elements are stored row-major with O(1) access via `aref`, and arrays are compared by identity (`eq`), so two distinct arrays are never `equal`. `make-array` and `aref` are not first-class function values -- `#'make-array` is unavailable, so call it directly.

`:fill-pointer` (rank-1 only) gives the vector a [fill pointer](fill-pointer.md): an integer sets it to that position, `t` to the vector size. The fill pointer is the effective length -- `length` and printing stop at it, while `aref` still reaches the full storage -- and is what [`vector-push`](vector-push.md)/[`vector-pop`](vector-pop.md)/[`vector-push-extend`](vector-push-extend.md) operate on. `:adjustable` marks the array adjustable, reported verbatim by [`adjustable-array-p`](adjustable-array-p.md); an adjustable array is resized in place by [`adjust-array`](adjust-array.md). `:initial-contents` fills the array from a list (row-major; on the compiled backends rank-1 only). `:element-type 'double-float`/`'single-float` (with no fill pointer/adjustability/displacement) selects the packed float representation, and `:element-type 'character` under the same conditions builds a **string** (a rank-1 character array IS a string, the [`make-string`](make-string.md) result shape). Any other element type is accepted but ignored (element types are not otherwise tracked; [`array-element-type`](array-element-type.md) returns `t` for general arrays).

`:displaced-to` builds a view over another array's storage instead of allocating one: element `i` (row-major) of the view reads and writes element `i + offset` of the target, where `:displaced-index-offset` defaults to 0, so changes are visible in both directions. The view has its own dimensions (they may differ in rank from the target's, e.g. a vector view over a matrix row), must fit inside the target, and is inspected with [`array-displacement`](array-displacement.md). A displaced view cannot be combined with `:fill-pointer`, `:adjustable` or `:initial-element`, and cannot itself be adjusted.

```lisp
(let ((a (make-array 3 :initial-element 0)))
  (aref a 0)) ; => 0
(length (make-array 5 :fill-pointer 2 :initial-element 0)) ; => 2
(let* ((base (make-array 4 :initial-element 1))
       (view (make-array 2 :displaced-to base :displaced-index-offset 1)))
  (setf (aref view 0) 9)
  (aref base 1)) ; => 9
```
