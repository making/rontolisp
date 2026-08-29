# geom:revolution

`(geom:revolution profile &key sides color label)`

A cross-section in the x >= 0 half of the xz-plane turned about z. End caps are added only where an end of the profile does not reach the axis. `geom:sphere` and `geom:torus` are both this. See the [solid modeling guide](../../guides/solid-modeling.md).

```lisp
(round (geom:volume (geom:revolution '((10 0 0) (10 0 20) (0 0 20)) :sides 64)))
; => 6273
```
