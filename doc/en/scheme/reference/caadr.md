# caadr

`(caadr pair)`

`(caadr x)` is `(car (car (cdr x)))`.

```scheme
(caadr '(1 (2 3) 4)) ; => 2
```
