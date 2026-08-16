# svref

`(svref vector index)`

Returns the element of a rank-1 array at the given 0-based `index`. It behaves like [`aref`](aref.md) restricted to exactly one subscript, and is likewise a `setf` place: `(setf (svref v i) value)` replaces an element. `#'svref` is a first-class function value, so it can be passed to `mapcar`/`funcall` like any other function.

```lisp
(svref (vector 10 20 30) 1) ; => 20
(let ((v (vector 1 2 3)))
  (setf (svref v 0) 99)
  (svref v 0)) ; => 99
```
