# geom:intersection

`(geom:intersection a b &key color label)`

A new solid covering only what both operands cover. The operands are taken in **world** coordinates and left untouched; the result is a new root solid, with `(geom:history result)` answering `(:intersection a b)`. Disjoint operands intersect to an **empty** solid -- no vertices, no facets, volume `0.0` -- rather than an error. See the [solid modeling guide](../../guides/solid-modeling.md).

```lisp
(let ((a (geom:box 100)) (b (geom:box 100)))
  (geom:translate b (geom:vec3 50 50 50))
  (geom:volume (geom:intersection a b)))
; => 125000.0
```
