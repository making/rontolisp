# geom:section

`(geom:section solid &key normal offset origin)`

The cross-section loops where a plane cuts the solid -- a cross-section drawing in one call. The plane is `:normal` (an axis keyword like `:z`, the default, or a vector) plus either `:offset` (the signed distance from the origin along the normal, default `0.0`) or `:origin` (a point on the plane). Each loop is a rank-2 `(n 3)` packed float32 array of **world** points, outer boundaries wound counter-clockwise seen from the normal's positive side and holes clockwise; a plane that misses the solid answers `nil`. A section of a torus at its equator is two loops -- the boundary and the hole. See the [solid modeling guide](../../guides/solid-modeling.md).

```lisp
(first (geom:section (geom:box '(100 200 300))))
; => #f((-50.0 -100.0 0.0) (50.0 -100.0 0.0) (50.0 100.0 0.0) (-50.0 100.0 0.0))
```
