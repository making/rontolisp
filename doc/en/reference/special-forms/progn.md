# progn

`(progn expr1 expr2...)`

Evaluates each expression in order and returns the value of the last one; the earlier expressions are evaluated only for their side effects. It is the way to group several forms where a single form is expected (such as a branch of `if`). An empty `(progn)` returns `nil`.

```lisp
(progn 1 2 3) ; => 3
```
