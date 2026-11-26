# caadar

`(caadar pair)`

`(caadar x)` is `(car (car (cdr (car x))))`.

```scheme
(caadar '((0 (1 2)) 3)) ; => 1
```
