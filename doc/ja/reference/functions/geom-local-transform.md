# geom:local-transform

`(geom:local-transform node)`

親から見たノード自身の変換。変更は `geom:move` / `geom:turn` / `geom:place` で行い、これらは新しい値に置き換えたうえで部分木全体のワールド変換のメモを破棄します。 [ソリッドモデリングガイド](../../guides/solid-modeling.md)を参照。

```lisp
(geom:translation-of (geom:local-transform (geom:make-node :translation (geom:vec3 1 2 3))))
; => #f(1.0 2.0 3.0)
```
