# geom:history

`(geom:history solid)`

What built the solid: `nil` for a primitive, `(op a b)` -- e.g. `(:union a b)` with the operand solids themselves -- for a boolean result. The operands are untouched by the operation, so a program can re-run a model at a different parameter by walking the history. See the [solid modeling guide](../../guides/solid-modeling.md).

```lisp
(let ((a (geom:box 10)) (b (geom:box 4)))
  (list (first (geom:history (geom:union a b))) (geom:history a)))
; => (:UNION NIL)
```
