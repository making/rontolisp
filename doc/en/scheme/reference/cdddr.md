# cdddr

`(cdddr pair)`

`(cdddr x)` is `(cdr (cdr (cdr x)))`: a list without its first three elements.

```scheme
(cdddr '(1 2 3 4)) ; => (4)
```
