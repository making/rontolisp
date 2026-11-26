# geom:parent-of

`(geom:parent-of node)`

このノードが接続されている親。ルートでは `nil` です。 [ソリッドモデリングガイド](../../guides/solid-modeling.md)を参照。

```lisp
(let* ((base (geom:make-node))
       (n (geom:make-node :parent base)))
  (eq (geom:parent-of n) base))
; => T
```
