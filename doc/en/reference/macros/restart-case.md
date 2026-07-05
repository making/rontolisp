# restart-case

`(restart-case form (restart-name (arg...) [option...] body...)...)`

Lite lowering to its primary `form` only. There is no restart/condition system, so the restart clauses are unreachable (nothing can `invoke-restart` them) and are discarded. The primary form is evaluated and its value returned; a form that signals (e.g. via [`error`](error.md)) signals as usual. Same "no condition system" semantics as [`check-type`](check-type.md)/[`assert`](assert.md).

```lisp
(restart-case (+ 1 2)
  (continue () 99)) ; => 3
```
