# caaadr

`(caaadr pair)`

`(caaadr x)` は `(car (car (car (cdr x))))` です。

```scheme
(caaadr '(0 ((1 2)) 3)) ; => 1
```
