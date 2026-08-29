# geom:history

`(geom:history solid)`

その立体を作ったもの: 基本形状なら `nil`、ブール演算の結果なら `(op a b)` -- 例えば `(:union a b)`、オペランドの立体そのものを含みます。演算はオペランドを変更しないので、プログラムは history を辿ってモデルを別のパラメータで作り直せます。[ソリッドモデリングガイド](../../guides/solid-modeling.md)を参照。

```lisp
(let ((a (geom:box 10)) (b (geom:box 4)))
  (list (first (geom:history (geom:union a b))) (geom:history a)))
; => (:UNION NIL)
```
