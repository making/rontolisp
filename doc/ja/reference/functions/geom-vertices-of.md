# geom:vertices-of

`(geom:vertices-of solid)`

ソリッドの頂点。モデル座標を並べたランク2 `(n 3)` のパックされた単精度配列です。点のリストではなく1つの配列にしていることが、立体全体の変換を1回の `linalg:matmul` にしています。 [ソリッドモデリングガイド](../../guides/solid-modeling.md)を参照。

```lisp
(linalg:shape (geom:vertices-of (geom:box 1)))
; => (8 3)
```
