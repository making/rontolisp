# geom:translation-of

`(geom:translation-of transform)`

The transform's translation, a packed single-float 3-vector. Read-only: a transform is a value. See the [solid modeling guide](../../guides/solid-modeling.md).

```lisp
(geom:translation-of (geom:make-transform :translation (geom:vec3 4 5 6)))
; => #f(4.0 5.0 6.0)
```
