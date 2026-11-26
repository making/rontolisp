# geom:reorient

`(geom:reorient node angle axis)`

Sets the node's rotation to `angle` radians about `axis`, keeping its translation -- `(geom:place node :axis axis :angle angle)` under a shorter name. See the [solid modeling guide](../../guides/solid-modeling.md).

```lisp
(let ((n (geom:make-node :translation (geom:vec3 1 2 3))))
  (geom:reorient n 0.0 :z)
  (geom:translation-of (geom:local-transform n)))
; => #f(1.0 2.0 3.0)
```
