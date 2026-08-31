# make-array

`(make-array dimensions &key initial-element initial-contents element-type fill-pointer adjustable displaced-to displaced-index-offset)`

Creates and returns a new array. `dimensions` is an integer for a rank-1 vector, or a list of integers for an array of any rank -- including the EMPTY list (`nil`), which builds a **rank-0 array**: Common Lisp's box for "a scalar seen as an array", holding one element that `aref` reads and writes with no subscripts at all. It prints as `#0A<datum>`, the syntax the reader accepts back (`#0A5`, `#0A(1 2)` -- a rank-0 array holding the list). `:initial-element` sets every cell to the given value, defaulting to nil. Elements are stored row-major with O(1) access via `aref`, and arrays are compared by identity (`eq`), so two distinct arrays are never `equal`. `make-array` and `aref` are not first-class function values -- `#'make-array` is unavailable, so call it directly.

`:fill-pointer` (rank-1 only) gives the vector a [fill pointer](fill-pointer.md): an integer sets it to that position, `t` to the vector size. The fill pointer is the effective length -- `length` and printing stop at it, while `aref` still reaches the full storage -- and is what [`vector-push`](vector-push.md)/[`vector-pop`](vector-pop.md)/[`vector-push-extend`](vector-push-extend.md) operate on. `:adjustable` marks the array adjustable, reported verbatim by [`adjustable-array-p`](adjustable-array-p.md); an adjustable array is resized in place by [`adjust-array`](adjust-array.md). `:initial-contents` fills the array from a (possibly nested) sequence, row-major, on every backend and at any rank. `:element-type 'double-float`/`'single-float` (with no fill pointer/adjustability/displacement) selects the packed float representation, and `:element-type 'character` under the same conditions builds a **string** (a rank-1 character array IS a string, the [`make-string`](make-string.md) result shape). `:element-type 'character` **with** `:fill-pointer`/`:adjustable` builds a fill-pointered mutable string on every backend: `vector-push-extend` of characters grows it, `replace` and `(setf (char ...))` write into it in place, and it prints, compares (`string=`/`equal`, `equal` hash keys) and passes `stringp` as a string. `:element-type 'character` with `:initial-contents` copies the contents (a string, a mutable string, or a character list) into a fresh simple string. `:element-type '(unsigned-byte 8)`, `'(unsigned-byte 16)` or `'(unsigned-byte 32)` on a rank-1 array (again with no fill pointer/adjustability/displacement, and written as a literal) selects a packed unsigned-integer vector: a store masks the value to the element width (two's-complement truncation) and a read returns it widened unsigned, a non-integer store is an error, and [`array-element-type`](array-element-type.md) reports the real `(unsigned-byte n)` specifier. [`subseq`](subseq.md) and `copy-seq` of a packed vector stay packed at the same width. A zero-parameter [`deftype`](../macros/deftype.md) name is resolved before any of these checks, so an alias selects exactly what its expansion would. Any other element type is accepted but ignored (element types are not otherwise tracked; [`array-element-type`](array-element-type.md) returns `t` for general arrays).

`:displaced-to` builds a view over another array's storage instead of allocating one: element `i` (row-major) of the view reads and writes element `i + offset` of the target, where `:displaced-index-offset` defaults to 0, so changes are visible in both directions. The view has its own dimensions (they may differ in rank from the target's, e.g. a vector view over a matrix row), must fit inside the target, and is inspected with [`array-displacement`](array-displacement.md). A displaced view cannot be combined with `:fill-pointer`, `:adjustable` or `:initial-element`, and cannot itself be adjusted.

Displacing onto a **string** answers a string view: the target decides the shape, so the result is `stringp`, has the target's characters from the offset on, prints and compares as a string, and `subseq`/`char`/`length` see the slice -- with no copy, which is how a portable library takes a shared substring. Writing through the view writes into the target (and a view of a view reaches the same characters). A string the running program allocated -- a [`make-string`](make-string.md) buffer, a `copy-seq`/[`subseq`](subseq.md) slice, a `concatenate 'string` / [`string-upcase`](string-upcase.md) / `format nil` / [`with-output-to-string`](../macros/with-output-to-string.md) / `read-line` result -- is mutable on every backend, so a view over it writes through. On the compiled backends a string **literal** (and the result of the few producers that still answer immutable values there, such as `princ-to-string`) is an immutable value that no write can reach; the view reads it without copying, and the first write through the view moves the view onto a mutable copy, leaving the original string as it was, exactly as `(setf (char s i) c)` on that string already does.

```lisp
(let ((a (make-array 3 :initial-element 0)))
  (aref a 0)) ; => 0
(length (make-array 5 :fill-pointer 2 :initial-element 0)) ; => 2
(let* ((base (make-array 4 :initial-element 1))
       (view (make-array 2 :displaced-to base :displaced-index-offset 1)))
  (setf (aref view 0) 9)
  (aref base 1)) ; => 9
(let* ((s (make-string 3 :initial-element #\a))
       (view (make-array 2 :element-type 'character :displaced-to s
                           :displaced-index-offset 1)))
  (setf (char view 0) #\X)
  (list view s)) ; => ("Xa" "aXa")
(let ((z (make-array nil :initial-element 5)))
  (list (array-rank z) (array-dimensions z) (array-total-size z) (aref z))) ; => (0 NIL 1 5)
(let ((z (make-array nil)))
  (setf (aref z) 7)
  z) ; => #0A7
(let ((bytes (make-array 3 :element-type '(unsigned-byte 8))))
  (setf (aref bytes 0) 300) ; stores 300 mod 256
  (aref bytes 0)) ; => 44
(progn
  (deftype octet () '(unsigned-byte 8))
  (array-element-type (make-array 3 :element-type 'octet))) ; => (UNSIGNED-BYTE 8)
```
