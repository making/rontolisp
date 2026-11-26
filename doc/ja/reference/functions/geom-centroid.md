# geom:centroid

`(geom:centroid solid)`

モデル座標でのソリッドの体積中心。`geom:volume` と同じ符号付き四面体の総和で求めます。 [ソリッドモデリングガイド](../../guides/solid-modeling.md)を参照。

```lisp
(coerce (geom:centroid (geom:box '(100 200 300))) 'list)
; => (0.0 0.0 0.0)
```
