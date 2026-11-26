# cdadar

`(cdadar pair)`

`(cdadar x)` は `(cdr (car (cdr (car x))))` です。

```scheme
(cdadar '((0 (1 2 3)))) ; => (2 3)
```
