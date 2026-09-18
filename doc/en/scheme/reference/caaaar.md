# caaaar

`(caaaar pair)`

`(caaaar x)` is `(car (car (car (car x))))`.

```scheme
(caaaar '((((1 2))) 3)) ; => 1
```
