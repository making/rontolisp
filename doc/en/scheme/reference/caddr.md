# caddr

`(caddr pair)`

`(caddr x)` is `(car (cdr (cdr x)))`: the third element of a list.

```scheme
(caddr '(1 2 3 4)) ; => 3
```
