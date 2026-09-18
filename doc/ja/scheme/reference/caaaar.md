# caaaar

`(caaaar pair)`

`(caaaar x)` は `(car (car (car (car x))))` です。

```scheme
(caaaar '((((1 2))) 3)) ; => 1
```
