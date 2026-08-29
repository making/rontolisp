# geom:surface-area

`(geom:surface-area solid)`

ソリッドのメッシュ三角形の面積の合計。 [ソリッドモデリングガイド](../../guides/solid-modeling.md)を参照。

```lisp
(geom:surface-area (geom:box '(100 200 300)))
; => 220000.0
```
