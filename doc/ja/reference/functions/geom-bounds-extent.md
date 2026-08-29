# geom:bounds-extent

`(geom:bounds-extent bounds)`

バウンディングボックスの各軸方向の大きさ。ビューアがカメラ距離を決めるのに使います。 [ソリッドモデリングガイド](../../guides/solid-modeling.md)を参照。

```lisp
(coerce (geom:bounds-extent (geom:bounds (geom:box '(100 200 300)))) 'list)
; => (100.0 200.0 300.0)
```
