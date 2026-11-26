# geom:transform-point

`(geom:transform-point transform point)`

The point carried through the transform: rotate, then translate. See the [solid modeling guide](../../guides/solid-modeling.md).

```lisp
(geom:transform-point (geom:make-transform :translation (geom:vec3 0 0 10)) (geom:vec3 1 2 3))
; => #f(1.0 2.0 13.0)
```
