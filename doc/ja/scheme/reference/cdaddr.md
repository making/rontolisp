# cdaddr

`(cdaddr pair)`

`(cdaddr x)` は `(cdr (car (cdr (cdr x))))` です。

```scheme
(cdaddr '(0 1 (2 3 4))) ; => (3 4)
```
