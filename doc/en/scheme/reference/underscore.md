# _

`_`

Auxiliary syntax of `syntax-rules`: in a pattern it matches anything and binds nothing, so it may appear more than once. It means nothing on its own.

```scheme
(let-syntax ((second (syntax-rules () ((_ _ b . _) b)))) (second 1 2 3)) ; => 2
```
