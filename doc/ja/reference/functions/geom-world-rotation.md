# geom:world-rotation

`(geom:world-rotation node)`

ワールド座標でのノードの姿勢。`geom:world-transform` の回転成分で、その各行はワールドから見たノード自身の軸です。 [ソリッドモデリングガイド](../../guides/solid-modeling.md)を参照。

```lisp
(coerce (linalg:row (geom:world-rotation (geom:make-node)) 0) 'list)
; => (1.0 0.0 0.0)
```
