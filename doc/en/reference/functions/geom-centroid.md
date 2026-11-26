# geom:centroid

`(geom:centroid solid)`

The solid's centre of volume, in MODEL coordinates, by the same signed-tetrahedron sum as `geom:volume`. See the [solid modeling guide](../../guides/solid-modeling.md).

```lisp
(coerce (geom:centroid (geom:box '(100 200 300))) 'list)
; => (0.0 0.0 0.0)
```
