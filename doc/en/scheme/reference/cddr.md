# cddr

`(cddr pair)`

`(cddr x)` is `(cdr (cdr x))`: a list without its first two elements. As with `car`, an empty list along the way answers `()`.

```scheme
(cddr '(1 2 3)) ; => (3)
```
