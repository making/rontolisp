# geom:upper-of

`(geom:upper-of bounds)`

バウンディングボックスの最大側の角（3次元ベクトル）。 [ソリッドモデリングガイド](../../guides/solid-modeling.md)を参照。

```lisp
(coerce (geom:upper-of (geom:bounds (geom:box 10))) 'list)
; => (5.0 5.0 5.0)
```
