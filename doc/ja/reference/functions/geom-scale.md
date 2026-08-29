# geom:scale

`(geom:scale solid factor)`

モデル座標をすべて `factor` 倍し（破壊的）、キャッシュ済みのメッシュ・ワイヤフレーム・`geom:user-data` を破棄します。パッケージが提供する唯一の頂点変更であり、無効化が必要な唯一の箇所です。 [ソリッドモデリングガイド](../../guides/solid-modeling.md)を参照。

```lisp
(let ((s (geom:box 10)))
  (geom:scale s 2)
  (geom:volume s))
; => 8000.0
```
