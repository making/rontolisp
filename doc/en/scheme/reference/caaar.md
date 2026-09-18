# caaar

`(caaar pair)`

`(caaar x)` is `(car (car (car x)))`.

```scheme
(caaar '(((1 2) 3) 4)) ; => 1
```
