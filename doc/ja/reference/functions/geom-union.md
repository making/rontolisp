# geom:union

`(geom:union a b &key color label)`

両オペランドのいずれかが占める領域全体を覆う新しい立体。オペランドは**ワールド**座標で扱われ (配置済みの 2 つの立体の和は、両方を配置した後の見た目そのもの)、変更されません。結果は頂点がワールド座標の新しいルート立体で、`(geom:history result)` は `(:union a b)` を返します。`:color` の既定は `a` の色です。分類の許容誤差は `geom:*tolerance*` (既定 `1.0e-5`) で、オペランドを合わせたバウンディングボックスに対する**相対値**なので、0.001 スケールのモデルも 1000 スケールのモデルも扱えます。[ソリッドモデリングガイド](../../guides/solid-modeling.md)を参照。

```lisp
(let ((a (geom:box 100)) (b (geom:box 100)))
  (geom:move b (geom:vec3 50 50 50))
  (geom:volume (geom:union a b)))
; => 1875000.0
```
