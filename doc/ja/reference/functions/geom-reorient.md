# geom:reorient

`(geom:reorient node angle axis)`

ノードの回転を `axis` まわりの `angle` ラジアンに設定し、並進は保ちます。`(geom:place node :axis axis :angle angle)` の短い綴りです。 [ソリッドモデリングガイド](../../guides/solid-modeling.md)を参照。

```lisp
(let ((n (geom:make-node :translation (geom:vec3 1 2 3))))
  (geom:reorient n 0.0 :z)
  (geom:translation-of (geom:local-transform n)))
; => #f(1.0 2.0 3.0)
```
