# geom:difference

`(geom:difference a b &key color label)`

A new solid: `a` with `b` removed -- a plate with a bolt hole, a block with a slot milled in it. The operands are taken in **world** coordinates and left untouched; the result is a new root solid, with `(geom:history result)` answering `(:difference a b)`. A hole exactly as deep as the plate is thick goes all the way through: coplanar faces are a handled case, not a corner one. See the [solid modeling guide](../../guides/solid-modeling.md).

```lisp
(let ((plate (geom:box '(100 100 20)))
      (hole (geom:cylinder :radius 10 :height 20 :sides 24)))
  (geom:move hole (geom:vec3 0 0 -10))
  (round (geom:volume (geom:difference plate hole))))
; => 193788
```
