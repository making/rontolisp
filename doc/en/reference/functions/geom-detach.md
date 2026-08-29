# geom:detach

`(geom:detach child)`

Takes `child` out of its parent's frame; its local transform becomes its world transform. Answers the child. See the [solid modeling guide](../../guides/solid-modeling.md).

```lisp
(let* ((base (geom:make-node :translation (geom:vec3 0 0 500)))
       (n (geom:make-node :translation (geom:vec3 1 2 3) :parent base)))
  (geom:detach n)
  (geom:world-translation n))
; => #f(1.0 2.0 3.0)
```
