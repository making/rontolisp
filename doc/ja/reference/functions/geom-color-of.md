# geom:color-of

`(geom:color-of solid)`

ソリッドの色。成分 0..1 の3次元ベクトルで、`setf` で設定できます。頂点単位ではなく立体単位なので、レンダラはユニフォームとして GPU に渡し、メッシュのバイト数は増えません。 [ソリッドモデリングガイド](../../guides/solid-modeling.md)を参照。

```lisp
(let ((s (geom:box 1 :color (geom:vec3 1 0 0))))
  (coerce (geom:color-of s) 'list))
; => (1.0 0.0 0.0)
```
