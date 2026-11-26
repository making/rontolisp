# caadr

`(caadr pair)`

`(caadr x)` は `(car (car (cdr x)))` です。

```scheme
(caadr '(1 (2 3) 4)) ; => 2
```
