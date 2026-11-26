# geom:make-transform

`(geom:make-transform &key translation rotation rpy axis angle)`

A rigid motion: a translation 3-vector and a 3x3 rotation. A transform is a VALUE -- it has no parent, no identity and no cache, nothing mutates one in place, and the same one may be the local transform of any number of nodes. Give the rotation as `:rotation` (a matrix), `:rpy` (a three-element list) or `:axis` plus `:angle`. See the [solid modeling guide](../../guides/solid-modeling.md).

```lisp
(geom:translation-of (geom:make-transform :translation (geom:vec3 1 2 3) :axis :z :angle 0.5))
; => #f(1.0 2.0 3.0)
```
