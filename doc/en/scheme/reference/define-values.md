# define-values

`(define-values (variable...) expression)`

Evaluates `expression`, which must answer as many values as there are variables, and defines each variable to the corresponding value. It may appear at the top level or as an internal definition. A rest formal, `(define-values (a . rest) ...)` or `(define-values all ...)`, is not supported.

```scheme
(let () (define-values (q r) (values 17 5)) (list q r)) ; => (17 5)
(define-values (a b) (values 1 2))
(+ a b) ; => 3
```
