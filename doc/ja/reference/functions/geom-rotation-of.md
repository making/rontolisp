# geom:rotation-of

`(geom:rotation-of transform)`

変換の 3x3 回転行列（パックされた単精度配列）。transform は値なので読み出し専用です。 [ソリッドモデリングガイド](../../guides/solid-modeling.md)を参照。

```lisp
(coerce (linalg:row (geom:rotation-of (geom:make-transform)) 2) 'list)
; => (0.0 0.0 1.0)
```
