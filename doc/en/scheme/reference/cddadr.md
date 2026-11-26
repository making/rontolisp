# cddadr

`(cddadr pair)`

`(cddadr x)` is `(cdr (cdr (car (cdr x))))`.

```scheme
(cddadr '(0 (1 2 3 4))) ; => (3 4)
```
