# geom:mesh

`(geom:mesh solid)`

The solid's triangles in MODEL space: a packed single-float array, 18 floats a triangle (three corners of position + normal), fan-triangulated per facet with a Newell normal. Computed once and cached on the solid, because a rigid solid's triangles never change and only its pose does -- a renderer that re-tessellates per frame is 42x slower than one that uploads this once and hands the GPU the world transform as a per-draw uniform. See the [solid modeling guide](../../guides/solid-modeling.md).

```lisp
(length (geom:mesh (geom:box 1)))
; => 216
```
