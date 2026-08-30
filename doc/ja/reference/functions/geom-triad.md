# geom:triad

`(geom:triad &key length radius head-radius head-length sides at)`

座標系を表す3本の矢印 (+x 赤、+y 緑、+z 青、ラベルは `"x"` / `"y"` / `"z"`) を、ビューアのモードではなく呼び出し側が所有する [`geom:arrow`](geom-arrow.md) ソリッドのリストとして返します。`:at` は3本まとめて配置するので、`(geom:triad :at (geom:vec3 0 0 0))` が原点を示す指示子になります。追加は他のソリッドと同じで、[`scene:add`](scene-add.md) はリスト引数を展開するので `(scene:add *v* (geom:triad))` の 1 呼び出しです。既定の長さは、`scene:axes` の線の三つ組みがビューアの既定カメラ距離で描く長さです。 [ソリッドモデリングガイド](../../guides/solid-modeling.md)を参照。

```lisp
(mapcar #'geom:label-of (geom:triad :at (geom:vec3 0 0 0)))
; => ("x" "y" "z")
```
