# geom:arrow

`(geom:arrow &key length direction radius head-radius head-length sides color label)`

An arrow as a SOLID: a shaft and a pointed head, built as one shell rather than a union of a cylinder and a cone. The tail is the model origin and the tip is `length` along `direction` (`:x` / `:y` / `:z` / `:-x` ... or a vector). `:radius` is the shaft's, so its thickness is twice that; the head is a cone of `:head-radius` over the last `:head-length`. Every unstated measurement is a fraction of `:length`, so naming the length alone gives the same arrow at any size. Three of them are [`geom:triad`](geom-triad.md), the origin indicator. See the [solid modeling guide](../../guides/solid-modeling.md).

```lisp
(round (geom:volume (geom:arrow :length 200 :sides 24)))
; => 32201
```
