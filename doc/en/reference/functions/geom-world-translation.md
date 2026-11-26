# geom:world-translation

`(geom:world-translation node)`

The node's origin in world coordinates -- the translation of its `geom:world-transform`. See the [solid modeling guide](../../guides/solid-modeling.md).

```lisp
(let* ((base (geom:make-node :translation (geom:vec3 0 0 500)))
       (n (geom:make-node :translation (geom:vec3 1 2 3) :parent base)))
  (geom:world-translation n))
; => #f(1.0 2.0 503.0)
```
