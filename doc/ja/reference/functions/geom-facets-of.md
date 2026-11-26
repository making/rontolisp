# geom:facets-of

`(geom:facets-of solid)`

ソリッドの面。`geom:vertices-of` へのインデックスループのリストで、それぞれ外側から見て反時計回りに巻かれています。 [ソリッドモデリングガイド](../../guides/solid-modeling.md)を参照。

```lisp
(geom:facets-of (geom:box 1))
; => ((3 2 1 0) (4 5 6 7) (0 1 5 4) (1 2 6 5) (2 3 7 6) (3 0 4 7))
```
