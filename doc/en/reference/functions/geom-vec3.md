# geom:vec3

`(geom:vec3 x y z)`

A packed single-float 3-vector, the coordinate type the whole package speaks. float32 is what a GPU vertex buffer holds, so a `geom` value reaches Metal through `objc:data` with no conversion, and every `linalg` transform preserves the width. See the [solid modeling guide](../../guides/solid-modeling.md).

```lisp
(geom:vec3 1 2 3)
; => #f(1.0 2.0 3.0)
```
