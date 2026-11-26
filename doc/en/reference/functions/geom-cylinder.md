# geom:cylinder

`(geom:cylinder &key radius height sides color label)`

A cylinder standing on z = 0, its origin at the centre of the base. `:sides` is the tessellation: the solid is inscribed in the smooth cylinder, so a measured volume converges on the closed form from below. See the [solid modeling guide](../../guides/solid-modeling.md).

```lisp
(round (geom:volume (geom:cylinder :radius 50 :height 100 :sides 64)))
; => 784137
```
