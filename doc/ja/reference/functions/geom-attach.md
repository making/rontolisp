# geom:attach

`(geom:attach parent child)`

`child` を `parent` にぶら下げます。以後、子の姿勢は親の座標系で解釈されます。子を返します。 [ソリッドモデリングガイド](../../guides/solid-modeling.md)を参照。

```lisp
(let ((base (geom:make-node :translation (geom:vec3 0 0 500)))
      (n (geom:make-node :translation (geom:vec3 1 2 3))))
  (geom:attach base n)
  (geom:world-translation n))
; => #f(1.0 2.0 503.0)
```
