# geom:intersection

`(geom:intersection a b &key color label)`

両オペランドが共通に占める領域だけを覆う新しい立体。オペランドは**ワールド**座標で扱われ、変更されません。結果は新しいルート立体で、`(geom:history result)` は `(:intersection a b)` を返します。交わらないオペランドの積はエラーではなく**空**の立体 (頂点なし、面なし、体積 `0.0`) になります。[ソリッドモデリングガイド](../../guides/solid-modeling.md)を参照。

```lisp
(let ((a (geom:box 100)) (b (geom:box 100)))
  (geom:translate b (geom:vec3 50 50 50))
  (geom:volume (geom:intersection a b)))
; => 125000.0
```
