# muffle-warning

`(muffle-warning [condition])`

Invokes the `muffle-warning` restart that every [`warn`](../macros/warn.md) establishes in a restart-system program, aborting the pending warning **before it is printed** — `warn` then returns `nil` silently. Meant to be called from a [`handler-bind`](../macros/handler-bind.md) handler on `warning`. Signals an error when no `muffle-warning` restart is active (i.e. outside a `warn`).

```lisp
(handler-bind ((warning (lambda (w) (muffle-warning))))
  (list :done (warn "nothing to see"))) ; => (:DONE NIL)
```
