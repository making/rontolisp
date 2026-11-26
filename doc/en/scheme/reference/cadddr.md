# cadddr

`(cadddr pair)`

`(cadddr x)` is `(car (cdr (cdr (cdr x))))`: the fourth element of a list.

```scheme
(cadddr '(1 2 3 4 5)) ; => 4
```
