# geom:extrusion

`(geom:extrusion profile &key along color label)`

z = 0 上の閉じた輪郭をベクトルに沿って掃引した一般の角柱。`geom:cylinder` もこれで作られています。`profile` は +z から見て反時計回りに並べた `(x y z)` 点のリスト、`:along` は高さかオフセットベクトルです。 [ソリッドモデリングガイド](../../guides/solid-modeling.md)を参照。

```lisp
(geom:volume (geom:extrusion '((0 0 0) (4 0 0) (4 3 0) (0 3 0)) :along 10))
; => 120.0
```
