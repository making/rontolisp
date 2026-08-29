# geom:vertices-of

`(geom:vertices-of solid)`

The solid's vertices: a rank-2 `(n 3)` packed single-float array of MODEL coordinates. One array rather than a list of points is what makes a whole-solid transform a single `linalg:matmul`. See the [solid modeling guide](../../guides/solid-modeling.md).

```lisp
(linalg:shape (geom:vertices-of (geom:box 1)))
; => (8 3)
```
