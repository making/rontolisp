# cdar

`(cdar pair)`

`(cdar x)` is `(cdr (car x))`. As with `car`, an empty list along the way answers `()`.

```scheme
(cdar '((1 2) 3)) ; => (2)
```
