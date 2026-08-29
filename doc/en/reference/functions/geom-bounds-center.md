# geom:bounds-center

`(geom:bounds-center bounds)`

The midpoint of a bounding box -- what a viewer points its camera at. See the [solid modeling guide](../../guides/solid-modeling.md).

```lisp
(let ((b (geom:box 10)))
  (geom:move b (geom:vec3 100 0 0))
  (coerce (geom:bounds-center (geom:bounds b)) 'list))
; => (100.0 0.0 0.0)
```
