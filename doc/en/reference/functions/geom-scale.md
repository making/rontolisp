# geom:scale

`(geom:scale solid factor)`

A **new** solid whose model coordinates are `factor` times the operand's -- functional, like the booleans beside it, with the operand left untouched. The copy carries the facets, colour and label and records `(:scale s factor)` in its history; it is a fresh, unattached root solid, so parent, children and `geom:user-data` are not carried -- a solid already in a viewer wants the destructive `geom:nscale` instead. `factor` is a number, or a 3-vector or list for a non-uniform scale; a mirroring factor (negative determinant) flips the facets so the winding stays counter-clockwise seen from outside, and a zero component is refused. See the [solid modeling guide](../../guides/solid-modeling.md).

```lisp
(let* ((s (geom:box 10))
       (c (geom:scale s '(1 2 3))))
  (list (geom:volume c) (geom:volume s)))
; => (6000.0 1000.0)
```
