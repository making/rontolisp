# geom:axis-angle-matrix

`(geom:axis-angle-matrix angle axis)`

The 3x3 rotation of `angle` radians about `axis`, by Rodrigues' formula. The axis is normalized, so any non-zero vector works as well as a keyword. See the [solid modeling guide](../../guides/solid-modeling.md).

```lisp
(mapcar (lambda (x) (round (* 1000 x)))
        (coerce (linalg:row (geom:axis-angle-matrix (/ 3.141592653589793 2) :z) 0) 'list))
; => (0 -1000 0)
```
