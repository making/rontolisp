# geom:box

`(geom:box size &key color label)`

A rectangular solid centred on its own origin. `size` is a scalar (a cube) or an `(x y z)` list -- the one measurement that names the shape, and the only positional argument in the whole constructor family. See the [solid modeling guide](../../guides/solid-modeling.md).

```lisp
(geom:volume (geom:box '(100 200 300)))
; => 6000000.0
```
