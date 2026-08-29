# geom:bounds-center

`(geom:bounds-center bounds)`

バウンディングボックスの中点。ビューアがカメラを向ける先です。 [ソリッドモデリングガイド](../../guides/solid-modeling.md)を参照。

```lisp
(let ((b (geom:box 10)))
  (geom:move b (geom:vec3 100 0 0))
  (coerce (geom:bounds-center (geom:bounds b)) 'list))
; => (100.0 0.0 0.0)
```
