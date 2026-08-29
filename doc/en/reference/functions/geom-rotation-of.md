# geom:rotation-of

`(geom:rotation-of transform)`

The transform's 3x3 rotation, a packed single-float array. Read-only: a transform is a value. See the [solid modeling guide](../../guides/solid-modeling.md).

```lisp
(coerce (linalg:row (geom:rotation-of (geom:make-transform)) 2) 'list)
; => (0.0 0.0 1.0)
```
