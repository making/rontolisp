# when

`(when condition body...)`

Evaluates `condition`; if it is truthy, evaluates the body forms in order and returns the value of the last one. If `condition` is nil, the body is skipped and `when` returns nil. It is shorthand for an `if` with no else branch and an implicit `progn` body.

```lisp
(when (> 5 3) 'yes) ; => YES
```
