# caddar

`(caddar pair)`

`(caddar x)` is `(car (cdr (cdr (car x))))`.

```scheme
(caddar '((0 1 2 3))) ; => 2
```
