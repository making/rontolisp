# geom:children-of

`(geom:children-of node)`

The nodes attached to this one, as a list. See the [solid modeling guide](../../guides/solid-modeling.md).

```lisp
(let* ((base (geom:make-node))
       (n (geom:make-node :parent base)))
  (eq (first (geom:children-of base)) n))
; => T
```
