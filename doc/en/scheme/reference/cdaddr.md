# cdaddr

`(cdaddr pair)`

`(cdaddr x)` is `(cdr (car (cdr (cdr x))))`.

```scheme
(cdaddr '(0 1 (2 3 4))) ; => (3 4)
```
