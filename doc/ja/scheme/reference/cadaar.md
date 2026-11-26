# cadaar

`(cadaar pair)`

`(cadaar x)` は `(car (cdr (car (car x))))` です。

```scheme
(cadaar '(((0 1 2)) 3)) ; => 1
```
