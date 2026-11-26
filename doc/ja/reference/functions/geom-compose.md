# geom:compose

`(geom:compose outer inner)`

`inner` の運動を `outer` の座標系に運ぶ変換、すなわち `inner` に `outer` を適用したもの。シーングラフがワールド変換を組み立てるときの演算です。新しい transform を返し、引数は変更しません。 [ソリッドモデリングガイド](../../guides/solid-modeling.md)を参照。

```lisp
(geom:translation-of (geom:compose (geom:make-transform :translation (geom:vec3 0 0 10))
                                   (geom:make-transform :translation (geom:vec3 1 2 3))))
; => #f(1.0 2.0 13.0)
```
