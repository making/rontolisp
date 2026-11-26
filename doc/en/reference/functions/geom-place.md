# geom:place

`(geom:place node &key transform translation rotation rpy axis angle)`

Sets the node's pose outright rather than accumulating -- what an animation loop wants, since repeated `geom:rotate` deltas drift. An omitted half (translation or rotation) is kept; `:transform` replaces the whole local transform with a value you already hold. See the [solid modeling guide](../../guides/solid-modeling.md).

```lisp
(let ((n (geom:make-node)))
  (geom:rotate n 1.0 :z)
  (geom:place n :axis :z :angle 0.0)
  (coerce (linalg:row (geom:world-rotation n) 0) 'list))
; => (1.0 0.0 0.0)
```
