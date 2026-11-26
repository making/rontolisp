# invoke-restart

`(invoke-restart restart arg...)`

Invokes a restart with the given arguments. `restart` is a restart **name** (symbol or keyword — the innermost active restart with that name wins) or a restart **object** from [`find-restart`](find-restart.md)/[`compute-restarts`](compute-restarts.md). For a [`restart-case`](../macros/restart-case.md) restart, control transfers non-locally to the establishing frame and the clause body runs with `arg...`; for a [`restart-bind`](../macros/restart-bind.md) restart, the bound function is called at the invocation point and its value returned. Signals an error when no matching restart is active.

```lisp
(handler-bind ((error (lambda (c) (invoke-restart :use-value 7))))
  (restart-case (error "no value")
    (:use-value (v) (list :used v)))) ; => (:USED 7)
```
