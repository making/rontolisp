# geom:upper-of

`(geom:upper-of bounds)`

The maximum corner of a bounding box, a 3-vector. See the [solid modeling guide](../../guides/solid-modeling.md).

```lisp
(coerce (geom:upper-of (geom:bounds (geom:box 10))) 'list)
; => (5.0 5.0 5.0)
```
