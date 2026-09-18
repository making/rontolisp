# letrec-syntax

`(letrec-syntax ((keyword (syntax-rules ...))...) body...)`

Like `let-syntax`, but the transformers see every `keyword` it binds, so the macros may use themselves and each other.

```scheme
(letrec-syntax ((my-or (syntax-rules () ((_) #f) ((_ e r ...) (let ((t e)) (if t t (my-or r ...))))))) (my-or #f 7)) ; => 7
```
