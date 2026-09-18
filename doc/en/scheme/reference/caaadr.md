# caaadr

`(caaadr pair)`

`(caaadr x)` is `(car (car (car (cdr x))))`.

```scheme
(caaadr '(0 ((1 2)) 3)) ; => 1
```
