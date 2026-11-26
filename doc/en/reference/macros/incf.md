# incf

`(incf place [delta])`

Increments the number stored in `place` by `delta` (default `1`), stores the result back into `place`, and returns the new value. `place` may be any location `setf` accepts. It expands into `(setf place (+ place delta))`.

```lisp
(let ((x 5)) (incf x 3)) ; => 8
```
