# geom:detach

`(geom:detach child)`

`child` を親の座標系から外します。ローカル変換がそのままワールド変換になります。子を返します。 [ソリッドモデリングガイド](../../guides/solid-modeling.md)を参照。

```lisp
(let* ((base (geom:make-node :translation (geom:vec3 0 0 500)))
       (n (geom:make-node :translation (geom:vec3 1 2 3) :parent base)))
  (geom:detach n)
  (geom:world-translation n))
; => #f(1.0 2.0 3.0)
```
