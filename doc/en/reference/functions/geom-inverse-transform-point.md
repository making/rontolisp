# geom:inverse-transform-point

`(geom:inverse-transform-point transform point)`

The point carried back through the transform, without building the inverse: subtract the translation, then rotate by the transpose. See the [solid modeling guide](../../guides/solid-modeling.md).

```lisp
(geom:inverse-transform-point (geom:make-transform :translation (geom:vec3 0 0 10)) (geom:vec3 1 2 13))
; => #f(1.0 2.0 3.0)
```
