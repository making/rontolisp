# geom:polyhedron

`(geom:polyhedron points facets &key color label)`

The escape hatch: raw points and index loops, for a mesh that came from an algorithm or a file. Each facet is a list of indexes into `points`, wound counter-clockwise seen from OUTSIDE -- get that backwards and `geom:volume` subtracts instead of adding. See the [solid modeling guide](../../guides/solid-modeling.md).

```lisp
(round (* 6 (geom:volume (geom:polyhedron '((0 0 0) (1 0 0) (0 1 0) (0 0 1))
                                          '((0 2 1) (0 1 3) (0 3 2) (1 2 3))))))
; => 1
```
