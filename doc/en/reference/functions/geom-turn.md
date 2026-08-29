# geom:turn

`(geom:turn node angle axis &key frame)`

Rotates the node by `angle` radians about `axis`, accumulating on its current orientation. `:frame` is `:local` (default) or `:parent`, as for `geom:move`. For an animation loop prefer `geom:place`, which sets the pose outright and cannot drift. See the [solid modeling guide](../../guides/solid-modeling.md).

```lisp
(let ((n (geom:make-node)))
  (geom:turn n (/ 3.141592653589793 2) :z)
  (mapcar (lambda (x) (round (* 1000 x))) (coerce (linalg:row (geom:world-rotation n) 0) 'list)))
; => (0 -1000 0)
```
