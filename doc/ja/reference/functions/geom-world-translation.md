# geom:world-translation

`(geom:world-translation node)`

ワールド座標でのノードの原点。`geom:world-transform` の並進成分です。 [ソリッドモデリングガイド](../../guides/solid-modeling.md)を参照。

```lisp
(let* ((base (geom:make-node :translation (geom:vec3 0 0 500)))
       (n (geom:make-node :translation (geom:vec3 1 2 3) :parent base)))
  (geom:world-translation n))
; => #f(1.0 2.0 503.0)
```
