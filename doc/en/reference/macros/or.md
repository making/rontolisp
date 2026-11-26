# or

`(or expr1 expr2...)`

Evaluates its expressions left to right, short-circuiting as soon as one returns a non-nil value and yielding that value; if every expression is nil it returns nil. `(or)` with no arguments returns nil. It expands into nested `if` forms, so expressions after the first truthy one are not evaluated.

```lisp
(or nil 2 3) ; => 2
```
