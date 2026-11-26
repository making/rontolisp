# geom:label-of

`(geom:label-of solid)`

The solid's label, whatever the caller passed as `:label` (`nil` by default). Settable with `setf`; nothing in `geom` interprets it. See the [solid modeling guide](../../guides/solid-modeling.md).

```lisp
(geom:label-of (geom:box 1 :label "lid"))
; => "lid"
```
