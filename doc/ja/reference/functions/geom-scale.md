# geom:scale

`(geom:scale solid factor)`

モデル座標を `factor` 倍した**新しい**ソリッドを返します。隣に並ぶブーリアン演算と同じく関数的で、オペランドは変更されません。コピーは面・色・ラベルを引き継ぎ、`history` に `(:scale s factor)` を記録します。親にも子にも繋がれていない新しいルートソリッドなので、parent・children・`geom:user-data` は引き継がれません。ビューアに入っているソリッドには破壊的な `geom:nscale` を使ってください。`factor` は数値、または非一様スケール用の3次元ベクトルかリストです。鏡映になる係数（行列式が負）では、巻き方が外側から見て反時計回りのままになるよう面を反転します。成分 0 は拒否されます。 [ソリッドモデリングガイド](../../guides/solid-modeling.md)を参照。

```lisp
(let* ((s (geom:box 10))
       (c (geom:scale s '(1 2 3))))
  (list (geom:volume c) (geom:volume s)))
; => (6000.0 1000.0)
```
