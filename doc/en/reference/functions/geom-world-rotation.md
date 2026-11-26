# geom:world-rotation

`(geom:world-rotation node)`

The node's orientation in world coordinates -- the rotation of its `geom:world-transform`. Its rows are the node's own axes seen from the world. See the [solid modeling guide](../../guides/solid-modeling.md).

```lisp
(coerce (linalg:row (geom:world-rotation (geom:make-node)) 0) 'list)
; => (1.0 0.0 0.0)
```
