# vector-push-extend

`(vector-push-extend value vector &optional extension)`

Like [`vector-push`](vector-push.md), but when the vector is full it grows the backing storage instead of returning nil, so the push always succeeds and the new index is returned. Any vector with a fill pointer can be grown, whether or not it was created `:adjustable` (matching common practice; [`adjustable-array-p`](adjustable-array-p.md) reports the flag verbatim). Signals an error when the vector has no fill pointer.

`extension` is the number of elements to grow by, added to the capacity verbatim. Without it the capacity DOUBLES (a zero-capacity vector grows to 1), so a push loop stays linear in the number of pushes. The new capacity is the same on every backend and is observable through [`array-dimension`](array-dimension.md).

A growth opens the slots between the pushed element and the new capacity. They are below the array dimension, so `aref` may read them, and they hold the vector's element type's own zero -- the same fill [`make-array`](make-array.md) gives an unsupplied element: `#\Space` for a character vector, `0` or `0.0` for a declared integer width or float type, `nil` for element type `t`.

```lisp
(defparameter *v* (make-array 1 :fill-pointer 0 :adjustable t))
(vector-push-extend 10 *v*) ; => 0
(vector-push-extend 20 *v* 4) ; => 1
*v* ; => #(10 20)
(array-dimension *v* 0) ; => 5
(aref *v* 4) ; => NIL
(defparameter *s* (make-array 1 :element-type 'character :fill-pointer 1 :adjustable t))
(vector-push-extend #\a *s* 4) ; => 1
(aref *s* 4) ; => #\Space
```
