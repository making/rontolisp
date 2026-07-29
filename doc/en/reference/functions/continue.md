# continue

`(continue [condition])`

Invokes the innermost active `continue` restart — the one a [`cerror`](../macros/cerror.md) establishes — and returns `nil` when none is active (unlike [`abort`](abort.md), this is not an error). Calling it from a [`handler-bind`](../macros/handler-bind.md) handler makes the interrupted `cerror` return `nil` and execution resume past it.

```lisp
(handler-bind ((error (lambda (c) (continue))))
  (list :after (cerror "Carry on." "recoverable problem"))) ; => (:AFTER NIL)
```
