# cdadar

`(cdadar pair)`

`(cdadar x)` is `(cdr (car (cdr (car x))))`.

```scheme
(cdadar '((0 (1 2 3)))) ; => (2 3)
```
