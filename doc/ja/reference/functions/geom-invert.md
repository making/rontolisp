# geom:invert

`(geom:invert transform)`

逆の剛体運動。変換とその逆をどちらの順に合成しても、float32 の精度で単位変換になります。 [ソリッドモデリングガイド](../../guides/solid-modeling.md)を参照。

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
