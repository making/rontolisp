# cdddar

`(cdddar pair)`

`(cdddar x)` は `(cdr (cdr (cdr (car x))))` です。

```scheme
(cdddar '((1 2 3 4 5))) ; => (4 5)
```
