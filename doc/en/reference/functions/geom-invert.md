# geom:invert

`(geom:invert transform)`

The inverse rigid motion. Composing a transform with its inverse in either order gives the identity, up to float32. See the [solid modeling guide](../../guides/solid-modeling.md).

```lisp
(mapcar (lambda (x) (round (* 1000 x)))
        (coerce (geom:transform-point
                  (geom:invert (geom:make-transform :translation (geom:vec3 1 2 3) :axis :z :angle 0.5))
                  (geom:transform-point
                    (geom:make-transform :translation (geom:vec3 1 2 3) :axis :z :angle 0.5)
                    (geom:vec3 7 -3 2)))
                'list))
; => (7000 -3000 2000)
```
