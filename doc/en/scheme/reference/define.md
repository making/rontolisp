# define

`(define variable expression)` `(define (name formals...) body...)` `(define (name . rest) body...)`

Binds `variable` to the value of `expression`; the second and third forms define a procedure, like `(define name (lambda formals body...))`. At the top level it creates or replaces a global binding, and a user definition of a built-in name such as `square` wins over the built-in. At the start of a body it is an internal definition, and the internal definitions of a body behave like `letrec*`. A definition has no value, so the REPL shows nothing. The curried form `(define ((f a) b) ...)` is not supported.

```scheme
(let () (define x 2) (* x 3)) ; => 6
(define (square2 n) (* n n))
(square2 5) ; => 25
```
