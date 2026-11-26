# geom:transform-point

`(geom:transform-point transform point)`

点を変換で運んだ結果。回転してから並進します。 [ソリッドモデリングガイド](../../guides/solid-modeling.md)を参照。

```lisp
(geom:transform-point (geom:make-transform :translation (geom:vec3 0 0 10)) (geom:vec3 1 2 3))
; => #f(1.0 2.0 13.0)
```
