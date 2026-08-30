# geom:wireframe

`(geom:wireframe solid)`

The solid's edges in MODEL space, each one once: a packed single-float array, 6 floats a segment. Cached on the solid like `geom:mesh`, and dropped by the same `geom:nscale`. See the [solid modeling guide](../../guides/solid-modeling.md).

```lisp
(length (geom:wireframe (geom:box 1)))
; => 72
```
