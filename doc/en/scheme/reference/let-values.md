# let-values

`(let-values ((formals expression)...) body...)`

Evaluates each `expression`, which answers multiple values, and binds them to the matching `formals`, then evaluates `body`. `formals` has the shape of a `lambda` parameter list, so `(a . rest)` and a single variable collect the remaining values as a list.

```scheme
(let-values (((q r) (values 17 5)) ((s) (values 'x))) (list q r s)) ; => (17 5 x)
(let-values (((a . rest) (values 1 2 3))) (list a rest)) ; => (1 (2 3))
```
