# and

`(and expr1 expr2...)`

Evaluates its expressions left to right, short-circuiting as soon as one returns nil and yielding that nil; if every expression is non-nil it returns the value of the last one. `(and)` with no arguments returns `t`. It expands into nested `if` forms, so later expressions are not evaluated once a nil is found.

```lisp
(and 1 2 3) ; => 3
```
