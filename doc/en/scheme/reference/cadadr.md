# cadadr

`(cadadr pair)`

`(cadadr x)` is `(car (cdr (car (cdr x))))`.

```scheme
(cadadr '(0 (1 2 3))) ; => 2
```
