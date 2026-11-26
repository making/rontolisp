# cdddar

`(cdddar pair)`

`(cdddar x)` is `(cdr (cdr (cdr (car x))))`.

```scheme
(cdddar '((1 2 3 4 5))) ; => (4 5)
```
