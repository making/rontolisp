# caaddr

`(caaddr pair)`

`(caaddr x)` は `(car (car (cdr (cdr x))))` です。

```scheme
(caaddr '(0 1 (2 3))) ; => 2
```
