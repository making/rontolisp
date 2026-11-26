# geom:axis-vector

`(geom:axis-vector axis)`

軸指定子が表す単位ベクトル。`:x` / `:y` / `:z` / `:-x` / `:-y` / `:-z`、またはベクトルをそのまま返します。回転を取る入口はすべてこの形式の軸を受け取ります。 [ソリッドモデリングガイド](../../guides/solid-modeling.md)を参照。

```lisp
(geom:axis-vector :-y)
; => #f(0.0 -1.0 0.0)
```
