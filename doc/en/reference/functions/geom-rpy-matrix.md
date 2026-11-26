# geom:rpy-matrix

`(geom:rpy-matrix roll pitch yaw)`

The 3x3 rotation of a roll about x, then a pitch about y, then a yaw about z -- the convention `geom:make-transform` and `geom:place` take as `:rpy`. See the [solid modeling guide](../../guides/solid-modeling.md).

```lisp
(mapcar (lambda (x) (round (* 1000 x)))
        (coerce (linalg:row (geom:rpy-matrix 0 0 (/ 3.141592653589793 2)) 0) 'list))
; => (0 -1000 0)
```
