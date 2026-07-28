# cell-error-name

`(cell-error-name condition)`

The `name` slot of a `cell-error` condition -- the name of the cell that could not be accessed. Every `cell-error` subtype carries it, `unbound-variable`, `undefined-function` and [`unbound-slot`](../macros/slot-boundp.md) included.

```lisp
(defclass ce-box () ((v)))
(handler-case (slot-value (make-instance 'ce-box) 'v)
  (unbound-slot (e) (cell-error-name e))) ; => V
```
