# caddar

`(caddar pair)`

`(caddar x)` は `(car (cdr (cdr (car x))))` です。

```scheme
(caddar '((0 1 2 3))) ; => 2
```
