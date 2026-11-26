# cddddr

`(cddddr pair)`

`(cddddr x)` is `(cdr (cdr (cdr (cdr x))))`: a list without its first four elements.

```scheme
(cddddr '(1 2 3 4 5 6)) ; => (5 6)
```
