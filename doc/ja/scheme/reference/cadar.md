# cadar

`(cadar pair)`

`(cadar x)` は `(car (cdr (car x)))` です。

```scheme
(cadar '((1 2) 3)) ; => 2
```
