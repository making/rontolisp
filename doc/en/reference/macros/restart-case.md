# restart-case

`(restart-case form (restart-name (arg...) [option...] body...)...)`

Lite lowering to its primary `form` only. The **restart system** is not available (conditions themselves are — see [`handler-case`](handler-case.md)), so the restart clauses are unreachable (nothing can `invoke-restart` them) and are discarded. The primary form is evaluated and its value returned; a form that signals (e.g. via [`error`](error.md)) signals as usual and can be caught by an enclosing `handler-case`. Same "no restarts" semantics as [`check-type`](check-type.md)/[`assert`](assert.md).

```lisp
(restart-case (+ 1 2)
  (continue () 99)) ; => 3
```
