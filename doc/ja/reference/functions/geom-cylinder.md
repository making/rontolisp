# geom:cylinder

`(geom:cylinder &key radius height sides color label)`

z = 0 の上に立つ円柱。原点は底面の中心です。`:sides` は分割数で、多面体は滑らかな円柱に内接するため、体積は閉形式に下から収束します。 [ソリッドモデリングガイド](../../guides/solid-modeling.md)を参照。

```lisp
(round (geom:volume (geom:cylinder :radius 50 :height 100 :sides 64)))
; => 784137
```
