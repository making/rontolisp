# geom:mesh-triangle-count

`(geom:mesh-triangle-count solid)`

`geom:mesh` が返す三角形の数。長さを 18 で割った値です。 [ソリッドモデリングガイド](../../guides/solid-modeling.md)を参照。

```lisp
(geom:mesh-triangle-count (geom:sphere :radius 1 :sides 32 :stacks 16))
; => 1024
```
