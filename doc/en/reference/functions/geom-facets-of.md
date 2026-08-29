# geom:facets-of

`(geom:facets-of solid)`

The solid's facets: a list of index loops into `geom:vertices-of`, each wound counter-clockwise seen from outside. See the [solid modeling guide](../../guides/solid-modeling.md).

```lisp
(geom:facets-of (geom:box 1))
; => ((3 2 1 0) (4 5 6 7) (0 1 5 4) (1 2 6 5) (2 3 7 6) (3 0 4 7))
```
