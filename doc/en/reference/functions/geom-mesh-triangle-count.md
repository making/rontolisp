# geom:mesh-triangle-count

`(geom:mesh-triangle-count solid)`

How many triangles `geom:mesh` answers -- its length divided by 18. See the [solid modeling guide](../../guides/solid-modeling.md).

```lisp
(geom:mesh-triangle-count (geom:sphere :radius 1 :sides 32 :stacks 16))
; => 1024
```
