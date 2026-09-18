# cadar

`(cadar pair)`

`(cadar x)` is `(car (cdr (car x)))`.

```scheme
(cadar '((1 2) 3)) ; => 2
```
