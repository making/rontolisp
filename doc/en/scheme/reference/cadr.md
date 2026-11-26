# cadr

`(cadr pair)`

`(cadr x)` is `(car (cdr x))`: the second element of a list. As with `car`, an empty list along the way answers `()`.

```scheme
(cadr '(1 2 3)) ; => 2
```
