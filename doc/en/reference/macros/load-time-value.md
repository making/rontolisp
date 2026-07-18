# load-time-value

`(load-time-value form [read-only-p])`

Lite: expands to `form` itself, so the form is re-evaluated at each use instead of once at load time (equivalent for the pure table reads real libraries guard with it). `read-only-p` is accepted and ignored.

```lisp
(load-time-value (+ 1 2)) ; => 3
```
