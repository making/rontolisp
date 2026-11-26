# cdaar

`(cdaar pair)`

`(cdaar x)` は `(cdr (car (car x)))` です。

```scheme
(cdaar '(((1 2) 3) 4)) ; => (2)
```
