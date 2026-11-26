# caaddr

`(caaddr pair)`

`(caaddr x)` is `(car (car (cdr (cdr x))))`.

```scheme
(caaddr '(0 1 (2 3))) ; => 2
```
