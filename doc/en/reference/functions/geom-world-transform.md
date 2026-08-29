# geom:world-transform

`(geom:world-transform node)`

The node's transform in world coordinates: its ancestors' local transforms composed down to it. Memoized on the node and dropped down the whole subtree on any pose change, so a renderer may ask for it once per node per frame. See the [solid modeling guide](../../guides/solid-modeling.md).

```lisp
(geom:translation-of (geom:world-transform (geom:make-node :translation (geom:vec3 1 2 3))))
; => #f(1.0 2.0 3.0)
```
