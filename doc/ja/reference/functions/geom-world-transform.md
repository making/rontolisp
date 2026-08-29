# geom:world-transform

`(geom:world-transform node)`

ワールド座標系でのノードの変換。祖先のローカル変換を合成したものです。ノード上にメモ化され、姿勢が変わると部分木全体で破棄されるので、レンダラは1フレームにノードあたり1回問い合わせて構いません。 [ソリッドモデリングガイド](../../guides/solid-modeling.md)を参照。

```lisp
(geom:translation-of (geom:world-transform (geom:make-node :translation (geom:vec3 1 2 3))))
; => #f(1.0 2.0 3.0)
```
