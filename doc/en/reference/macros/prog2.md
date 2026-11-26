# prog2

`(prog2 first second body...)`

Evaluates `first`, then `second`, then any remaining body forms in order, and returns the value of `second`. It mirrors `prog1` but yields the *second* form's value, which is handy when the first form is purely a setup side effect.

```lisp
(prog2 1 2 3) ; => 2
```
