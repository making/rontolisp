# lambda

`(lambda formals body...)`

Answers a procedure. `formals` is a list of parameters `(a b)`, a single variable `args` that receives every argument as a list, or a dotted list `(a . rest)` whose last variable receives the remaining arguments. The body may begin with internal definitions. A procedure is written as `#<procedure>`. `#!optional` and `#!rest` are not supported.

```scheme
((lambda (x y) (+ x y)) 3 4) ; => 7
((lambda args args) 1 2 3) ; => (1 2 3)
((lambda (a . rest) rest) 1 2 3) ; => (2 3)
```
