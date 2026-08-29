# geom:local-transform

`(geom:local-transform node)`

The node's own transform, relative to its parent. Change it with `geom:move` / `geom:turn` / `geom:place`, which replace it with a fresh value and invalidate the memoized world transform of the whole subtree. See the [solid modeling guide](../../guides/solid-modeling.md).

```lisp
(geom:translation-of (geom:local-transform (geom:make-node :translation (geom:vec3 1 2 3))))
; => #f(1.0 2.0 3.0)
```
