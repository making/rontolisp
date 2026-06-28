# psetq

`(psetq v1 e1 v2 e2 ...)`

Parallel assignment: every right-hand side expression is evaluated first, and only then are the variables assigned, so each value is computed against the variables' old bindings. This makes it possible to swap or rotate variables in one step without a temporary. `psetq` always returns nil.

```lisp
(let ((a 1) (b 2)) (psetq a b b a) (list a b)) ; => (2 1)
```
