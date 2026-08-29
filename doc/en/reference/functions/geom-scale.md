# geom:scale

`(geom:scale solid factor)`

Multiplies every model coordinate by `factor`, in place, and drops the cached mesh, wireframe and `geom:user-data`. The only vertex mutation the package offers, and therefore the only place that has to invalidate. See the [solid modeling guide](../../guides/solid-modeling.md).

```lisp
(let ((s (geom:box 10)))
  (geom:scale s 2)
  (geom:volume s))
; => 8000.0
```
