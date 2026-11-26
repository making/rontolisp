# geom:translation-of

`(geom:translation-of transform)`

変換の並進成分（パックされた単精度3次元ベクトル）。transform は値なので読み出し専用です。 [ソリッドモデリングガイド](../../guides/solid-modeling.md)を参照。

```lisp
(geom:translation-of (geom:make-transform :translation (geom:vec3 4 5 6)))
; => #f(4.0 5.0 6.0)
```
