# geom:difference

`(geom:difference a b &key color label)`

`a` から `b` を取り除いた新しい立体 -- ボルト穴の開いた板、溝を削り出したブロック。オペランドは**ワールド**座標で扱われ、変更されません。結果は新しいルート立体で、`(geom:history result)` は `(:difference a b)` を返します。板の厚みとちょうど同じ深さの穴は完全に貫通します。同一平面上の面は特殊ケースではなく、扱える通常のケースです。[ソリッドモデリングガイド](../../guides/solid-modeling.md)を参照。

```lisp
(let ((plate (geom:box '(100 100 20)))
      (hole (geom:cylinder :radius 10 :height 20 :sides 24)))
  (geom:translate hole (geom:vec3 0 0 -10))
  (round (geom:volume (geom:difference plate hole))))
; => 193788
```
