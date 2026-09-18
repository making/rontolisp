# cddar

`(cddar pair)`

`(cddar x)` は `(cdr (cdr (car x)))` です。

```scheme
(cddar '((1 2 3) 4)) ; => (3)
```
