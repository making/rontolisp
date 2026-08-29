# geom:cone

`(geom:cone &key radius height sides apex color label)`

A cone over a `radius`-wide ring on z = 0. `:apex` names the tip outright, which is what makes an oblique cone; without it the tip is `height` up the z axis. See the [solid modeling guide](../../guides/solid-modeling.md).

```lisp
(round (geom:volume (geom:cone :radius 50 :height 120 :sides 64)))
; => 313655
```
