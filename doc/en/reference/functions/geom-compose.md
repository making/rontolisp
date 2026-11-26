# geom:compose

`(geom:compose outer inner)`

The transform that carries `inner`'s motion into `outer`'s frame -- `outer` applied to `inner`, the operation the scene graph composes a world transform with. A new transform; neither argument is touched. See the [solid modeling guide](../../guides/solid-modeling.md).

```lisp
(geom:translation-of (geom:compose (geom:make-transform :translation (geom:vec3 0 0 10))
                                   (geom:make-transform :translation (geom:vec3 1 2 3))))
; => #f(1.0 2.0 13.0)
```
