# vector-push-extend

`(vector-push-extend value vector &optional extension)`

Like [`vector-push`](vector-push.md), but when the vector is full it grows the backing storage (by at least `extension` elements, default 1) instead of returning nil, so the push always succeeds and the new index is returned. Any vector with a fill pointer can be grown, whether or not it was created `:adjustable` (matching common practice; [`adjustable-array-p`](adjustable-array-p.md) reports the flag verbatim). Signals an error when the vector has no fill pointer.

```lisp
(defparameter *v* (make-array 1 :fill-pointer 0 :adjustable t))
(vector-push-extend 10 *v*) ; => 0
(vector-push-extend 20 *v* 4) ; => 1
*v* ; => #(10 20)
```
