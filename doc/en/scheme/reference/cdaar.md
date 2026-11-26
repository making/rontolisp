# cdaar

`(cdaar pair)`

`(cdaar x)` is `(cdr (car (car x)))`.

```scheme
(cdaar '(((1 2) 3) 4)) ; => (2)
```
