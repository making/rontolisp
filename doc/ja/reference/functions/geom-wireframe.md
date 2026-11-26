# geom:wireframe

`(geom:wireframe solid)`

モデル空間でのソリッドの辺を各1回ずつ並べたもの。パックされた単精度配列で、1線分あたり 6 float です。`geom:mesh` と同様にソリッド上にキャッシュされ、`geom:nscale` で同時に破棄されます。 [ソリッドモデリングガイド](../../guides/solid-modeling.md)を参照。

```lisp
(length (geom:wireframe (geom:box 1)))
; => 72
```
