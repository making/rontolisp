# geom:extrusion

`(geom:extrusion profile &key along color label)`

A closed profile on z = 0 swept along a vector -- the general prism, and what `geom:cylinder` is built from. `profile` is a list of `(x y z)` points wound counter-clockwise seen from +z; `:along` is a height or an offset vector. See the [solid modeling guide](../../guides/solid-modeling.md).

```lisp
(geom:volume (geom:extrusion '((0 0 0) (4 0 0) (4 3 0) (0 3 0)) :along 10))
; => 120.0
```
