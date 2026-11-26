# caaar

`(caaar pair)`

`(caaar x)` は `(car (car (car x)))` です。

```scheme
(caaar '(((1 2) 3) 4)) ; => 1
```
