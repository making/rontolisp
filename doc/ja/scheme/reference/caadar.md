# caadar

`(caadar pair)`

`(caadar x)` は `(car (car (cdr (car x))))` です。

```scheme
(caadar '((0 (1 2)) 3)) ; => 1
```
