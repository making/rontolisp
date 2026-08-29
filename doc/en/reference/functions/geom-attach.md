# geom:attach

`(geom:attach parent child)`

Hangs `child` off `parent`, so the child's pose is read in the parent's frame from now on. Answers the child. See the [solid modeling guide](../../guides/solid-modeling.md).

```lisp
(let ((base (geom:make-node :translation (geom:vec3 0 0 500)))
      (n (geom:make-node :translation (geom:vec3 1 2 3))))
  (geom:attach base n)
  (geom:world-translation n))
; => #f(1.0 2.0 503.0)
```
