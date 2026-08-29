# geom:bounds

`(geom:bounds solid-or-list)`

The axis-aligned bounding box of a solid, or of a list of them, in WORLD coordinates -- so it follows the scene graph. Read it with `geom:lower-of` / `geom:upper-of` / `geom:bounds-center` / `geom:bounds-extent`. See the [solid modeling guide](../../guides/solid-modeling.md).

```lisp
(coerce (geom:bounds-extent (geom:bounds (geom:box '(100 200 300)))) 'list)
; => (100.0 200.0 300.0)
```
