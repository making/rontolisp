# letrec

`(letrec ((variable init)...) body...)`

Binds the variables so that every `init` can refer to all of them, which is how mutually recursive local procedures are written. Each `init` should be a `lambda` or otherwise not use the value of another variable of the same `letrec`.

```scheme
(letrec ((ev? (lambda (n) (if (= n 0) #t (od? (- n 1))))) (od? (lambda (n) (if (= n 0) #f (ev? (- n 1)))))) (ev? 10)) ; => #t
(letrec ((fact (lambda (n) (if (= n 0) 1 (* n (fact (- n 1))))))) (fact 5)) ; => 120
```
