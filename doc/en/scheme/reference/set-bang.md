# set!

`(set! variable expression)`

Stores the value of `expression` in `variable`, which must already be bound. Its value is unspecified, so the REPL shows nothing.

```scheme
(let ((x 1)) (set! x 2) x) ; => 2
(define counter 0)
(set! counter (+ counter 1))
counter ; => 1
```
