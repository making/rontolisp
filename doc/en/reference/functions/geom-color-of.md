# geom:color-of

`(geom:color-of solid)`

The solid's colour, a 3-vector of 0..1 components. Settable with `setf`. It is per solid rather than per vertex, so a renderer hands it to the GPU as a uniform and it costs the mesh no bytes. See the [solid modeling guide](../../guides/solid-modeling.md).

```lisp
(let ((s (geom:box 1 :color (geom:vec3 1 0 0))))
  (coerce (geom:color-of s) 'list))
; => (1.0 0.0 0.0)
```
