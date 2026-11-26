# geom:lower-of

`(geom:lower-of bounds)`

バウンディングボックスの最小側の角（3次元ベクトル）。 [ソリッドモデリングガイド](../../guides/solid-modeling.md)を参照。

```lisp
(coerce (geom:lower-of (geom:bounds (geom:box 10))) 'list)
; => (-5.0 -5.0 -5.0)
```
