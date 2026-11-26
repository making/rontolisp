# geom:cone

`(geom:cone &key radius height sides apex color label)`

z = 0 上の半径 `radius` の円を底面とする錐。`:apex` で頂点を直接指定でき、斜円錐が作れます。省略すると頂点は z 軸上の高さ `height` の位置です。 [ソリッドモデリングガイド](../../guides/solid-modeling.md)を参照。

```lisp
(round (geom:volume (geom:cone :radius 50 :height 120 :sides 64)))
; => 313655
```
