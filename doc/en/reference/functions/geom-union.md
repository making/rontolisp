# geom:union

`(geom:union a b &key color label)`

A new solid covering everything either operand covers. The operands are taken in **world** coordinates -- the union of two placed solids is what it looks like after both have been placed -- and are left untouched; the result is a new root solid whose vertices are world coordinates, with `(geom:history result)` answering `(:union a b)`. `:color` defaults to `a`'s. The classification tolerance is `geom:*tolerance*` (default `1.0e-5`), **relative** to the operands' combined bounding box, so both a 0.001-scale and a 1000-scale model survive. See the [solid modeling guide](../../guides/solid-modeling.md).

```lisp
(let ((a (geom:box 100)) (b (geom:box 100)))
  (geom:translate b (geom:vec3 50 50 50))
  (geom:volume (geom:union a b)))
; => 1875000.0
```
