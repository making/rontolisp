# geom:children-of

`(geom:children-of node)`

このノードに接続されている子ノードのリスト。 [ソリッドモデリングガイド](../../guides/solid-modeling.md)を参照。

```lisp
(let* ((base (geom:make-node))
       (n (geom:make-node :parent base)))
  (eq (first (geom:children-of base)) n))
; => T
```
