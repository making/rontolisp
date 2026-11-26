# geom:volume

`(geom:volume solid)`

The solid's volume, by the divergence theorem over its mesh triangles. It doubles as a winding check: a facet wound the wrong way SUBTRACTS, so a mis-wound solid answers a grossly wrong number rather than a slightly small one. A tessellated primitive is inscribed in its smooth ideal, so its volume converges on the closed form from below. See the [solid modeling guide](../../guides/solid-modeling.md).

```lisp
(geom:volume (geom:box '(100 200 300)))
; => 6000000.0
```
