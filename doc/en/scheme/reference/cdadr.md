# cdadr

`(cdadr pair)`

`(cdadr x)` is `(cdr (car (cdr x)))`.

```scheme
(cdadr '(1 (2 3) 4)) ; => (3)
```
