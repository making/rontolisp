# geom:sphere

`(geom:sphere &key radius sides stacks color label)`

A sphere centred on its own origin, tessellated as `stacks` latitude bands revolved into `sides` meridians. See the [solid modeling guide](../../guides/solid-modeling.md).

```lisp
(round (geom:volume (geom:sphere :radius 50 :sides 32 :stacks 24)))
; => 518015
```
