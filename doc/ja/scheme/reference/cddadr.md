# cddadr

`(cddadr pair)`

`(cddadr x)` は `(cdr (cdr (car (cdr x))))` です。

```scheme
(cddadr '(0 (1 2 3 4))) ; => (3 4)
```
