# unless

`(unless condition body...)`

The complement of `when`: it evaluates `condition` and, only when it is nil, evaluates the body forms in order and returns the value of the last one. If `condition` is truthy the body is skipped and `unless` returns nil. It expands into an `if` whose then/else roles are reversed.

```lisp
(unless (> 3 5) 'yes) ; => yes
```
