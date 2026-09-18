# cdadr

`(cdadr pair)`

`(cdadr x)` は `(cdr (car (cdr x)))` です。

```scheme
(cdadr '(1 (2 3) 4)) ; => (3)
```
