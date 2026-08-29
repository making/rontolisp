# geom:bounds-union

`(geom:bounds-union a b)`

両方を含む最小のボックス。`(geom:bounds (list ...))` はこれで畳み込みます。 [ソリッドモデリングガイド](../../guides/solid-modeling.md)を参照。

```lisp
(coerce (geom:bounds-extent (geom:bounds-union (geom:bounds (geom:box 10))
                                               (geom:bounds (geom:box 20))))
        'list)
; => (20.0 20.0 20.0)
```
