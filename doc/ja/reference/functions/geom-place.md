# geom:place

`(geom:place node &key transform translation rotation rpy axis angle)`

ノードの姿勢を積み重ねずに直接設定します。`geom:rotate` の差分を繰り返すとドリフトするため、アニメーションループではこちらを使います。省略した側（並進または回転）は保たれます。`:transform` は手元の変換値でローカル変換ごと置き換えます。 [ソリッドモデリングガイド](../../guides/solid-modeling.md)を参照。

```lisp
(let ((n (geom:make-node)))
  (geom:rotate n 1.0 :z)
  (geom:place n :axis :z :angle 0.0)
  (coerce (linalg:row (geom:world-rotation n) 0) 'list))
; => (1.0 0.0 0.0)
```
