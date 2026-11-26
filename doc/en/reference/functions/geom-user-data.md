# geom:user-data

`(geom:user-data solid)`

A slot a consumer hangs its own state on, settable with `setf` -- a renderer keeps its GPU buffers here, where they live beside the mesh they describe and a `geom:detach` cannot orphan them. `geom:nscale` clears it along with the caches. See the [solid modeling guide](../../guides/solid-modeling.md).

```lisp
(let ((s (geom:box 1)))
  (setf (geom:user-data s) (list :buffer 42))
  (geom:user-data s))
; => (:BUFFER 42)
```
