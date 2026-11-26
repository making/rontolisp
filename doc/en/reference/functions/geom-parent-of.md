# geom:parent-of

`(geom:parent-of node)`

The node this one is attached to, or `nil` at a root. See the [solid modeling guide](../../guides/solid-modeling.md).

```lisp
(let* ((base (geom:make-node))
       (n (geom:make-node :parent base)))
  (eq (geom:parent-of n) base))
; => T
```
