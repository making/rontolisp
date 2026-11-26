# cadadr

`(cadadr pair)`

`(cadadr x)` は `(car (cdr (car (cdr x))))` です。

```scheme
(cadadr '(0 (1 2 3))) ; => 2
```
