# geom:nscale

`(geom:nscale solid factor)`

The destructive `geom:scale`: multiplies the solid's own model coordinates by `factor` in place, drops the cached mesh, wireframe and `geom:user-data`, and answers the same solid. The one vertex mutation the package offers, and therefore the only place that has to invalidate. `factor` takes the same shapes as `geom:scale` -- a number, or a 3-vector or list for a non-uniform scale, with a mirroring factor flipping the facets and a zero component refused. See the [solid modeling guide](../../guides/solid-modeling.md).

```lisp
(let ((s (geom:box 10)))
  (geom:nscale s 2)
  (geom:volume s))
; => 8000.0
```
