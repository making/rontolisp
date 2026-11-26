# cdaadr

`(cdaadr pair)`

`(cdaadr x)` is `(cdr (car (car (cdr x))))`.

```scheme
(cdaadr '(0 ((1 2 3)))) ; => (2 3)
```
