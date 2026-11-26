# geom:torus

`(geom:torus &key radius tube sides rings color label)`

A torus in the xy-plane: a circle of radius `tube` (`:rings` segments) swept around a circle of radius `radius` (`:sides` segments). See the [solid modeling guide](../../guides/solid-modeling.md).

```lisp
(round (geom:volume (geom:torus :radius 60 :tube 20 :sides 48 :rings 24)))
; => 467012
```
