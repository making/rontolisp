# cadaar

`(cadaar pair)`

`(cadaar x)` is `(car (cdr (car (car x))))`.

```scheme
(cadaar '(((0 1 2)) 3)) ; => 1
```
