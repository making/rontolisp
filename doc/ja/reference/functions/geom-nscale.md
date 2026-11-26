# geom:nscale

`(geom:nscale solid factor)`

破壊的な `geom:scale` です。ソリッド自身のモデル座標をその場で `factor` 倍し、キャッシュ済みのメッシュ・ワイヤフレーム・`geom:user-data` を破棄して、同じソリッドを返します。パッケージが提供する唯一の頂点変更であり、無効化が必要な唯一の箇所です。`factor` の形は `geom:scale` と同じで、数値または非一様スケール用の3次元ベクトルかリストを取り、鏡映になる係数では面を反転し、成分 0 は拒否します。 [ソリッドモデリングガイド](../../guides/solid-modeling.md)を参照。

```lisp
(let ((s (geom:box 10)))
  (geom:nscale s 2)
  (geom:volume s))
; => 8000.0
```
