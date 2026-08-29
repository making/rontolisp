# geom:bounds-extent

`(geom:bounds-extent bounds)`

The size of a bounding box along each axis -- what a viewer sizes its camera distance from. See the [solid modeling guide](../../guides/solid-modeling.md).

```lisp
(coerce (geom:bounds-extent (geom:bounds (geom:box '(100 200 300)))) 'list)
; => (100.0 200.0 300.0)
```
