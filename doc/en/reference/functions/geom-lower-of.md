# geom:lower-of

`(geom:lower-of bounds)`

The minimum corner of a bounding box, a 3-vector. See the [solid modeling guide](../../guides/solid-modeling.md).

```lisp
(coerce (geom:lower-of (geom:bounds (geom:box 10))) 'list)
; => (-5.0 -5.0 -5.0)
```
