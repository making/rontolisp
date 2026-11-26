# geom:bounds-union

`(geom:bounds-union a b)`

The smallest box containing both -- what `(geom:bounds (list ...))` folds a model down with. See the [solid modeling guide](../../guides/solid-modeling.md).

```lisp
(coerce (geom:bounds-extent (geom:bounds-union (geom:bounds (geom:box 10))
                                               (geom:bounds (geom:box 20))))
        'list)
; => (20.0 20.0 20.0)
```
