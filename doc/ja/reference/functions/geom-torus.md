# geom:torus

`(geom:torus &key radius tube sides rings color label)`

xy 平面上のトーラス。半径 `tube` の円（`:rings` 分割）を半径 `radius` の円（`:sides` 分割）に沿って掃引します。 [ソリッドモデリングガイド](../../guides/solid-modeling.md)を参照。

```lisp
(round (geom:volume (geom:torus :radius 60 :tube 20 :sides 48 :rings 24)))
; => 467012
```
