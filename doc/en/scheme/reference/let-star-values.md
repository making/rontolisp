# let*-values

`(let*-values ((formals expression)...) body...)`

Like `let-values`, but binds the formals one clause after another, so each `expression` sees the variables bound by the clauses before it.

```scheme
(let*-values (((a b) (values 1 2)) ((c) (values (+ a b)))) (list a b c)) ; => (1 2 3)
```
