# geom:box

`(geom:box size &key color label)`

自身の原点を中心とする直方体。`size` はスカラー（立方体）か `(x y z)` のリストです。形状を決める唯一の寸法であり、コンストラクタ群で唯一の位置引数です。 [ソリッドモデリングガイド](../../guides/solid-modeling.md)を参照。

```lisp
(geom:volume (geom:box '(100 200 300)))
; => 6000000.0
```
