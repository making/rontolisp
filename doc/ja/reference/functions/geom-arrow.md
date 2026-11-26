# geom:arrow

`(geom:arrow &key length direction radius head-radius head-length sides color label)`

ソリッドとしての矢印。軸と尖った頭を、円柱と円錐の和ではなく1枚のシェルとして作ります。尾がモデル原点、先端は `direction` (`:x` / `:y` / `:z` / `:-x` ... またはベクトル) 方向に `length` の位置です。`:radius` は軸の半径なので太さはその2倍。頭は最後の `:head-length` の区間を占める半径 `:head-radius` の錐です。指定しなかった寸法はすべて `:length` に対する比率なので、長さだけ指定すればどの大きさでも同じ形になります。これを3本束ねたものが原点を示す [`geom:triad`](geom-triad.md) です。 [ソリッドモデリングガイド](../../guides/solid-modeling.md)を参照。

```lisp
(round (geom:volume (geom:arrow :length 200 :sides 24)))
; => 32201
```
