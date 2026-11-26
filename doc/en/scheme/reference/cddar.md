# cddar

`(cddar pair)`

`(cddar x)` is `(cdr (cdr (car x)))`.

```scheme
(cddar '((1 2 3) 4)) ; => (3)
```
