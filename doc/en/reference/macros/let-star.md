# let*

`(let* ((var1 init1) (var2 init2)...) body...)`

Like `let`, but the bindings are established sequentially: each `init` form can refer to the variables bound earlier in the same binding list. After all variables are bound the body forms are evaluated in order and the value of the last is returned. It expands into nested `let` forms, one per binding.

```lisp
(let* ((x 1) (y (+ x 1))) (list x y)) ; => (1 2)
```
