# let-syntax

`(let-syntax ((keyword (syntax-rules ...))...) body...)`

Binds each `keyword` to a macro for `body`, which is a body like `let`'s: its definitions are local. The transformers see the bindings around the `let-syntax`, not each other.

```scheme
(let-syntax ((double (syntax-rules () ((_ e) (* 2 e))))) (double 21)) ; => 42
(let ((x 'outer))
  (let-syntax ((m (syntax-rules () ((_) x))))
    (let ((x 'inner))
      (m)))) ; => outer
```
