# caar

`(caar pair)`

`(caar x)` is `(car (car x))`. As with `car`, an empty list along the way answers `()`.

```scheme
(caar '((1 2) 3)) ; => 1
```
