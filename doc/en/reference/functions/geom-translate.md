# geom:translate

`(geom:translate node offset &key frame)`

Translates the node by `offset`, accumulating onto its current pose. `:frame` is `:local` (the node's own axes, the default) or `:parent` (the axes it is attached to) -- named rather than positional, so a call site reading `:frame :parent` needs no manual. See the [solid modeling guide](../../guides/solid-modeling.md).

```lisp
(let ((n (geom:make-node :axis :z :angle (/ 3.141592653589793 2))))
  (geom:translate n (geom:vec3 10 0 0) :frame :parent)
  (mapcar (lambda (x) (round (* 1000 x))) (coerce (geom:world-translation n) 'list)))
; => (10000 0 0)
```
