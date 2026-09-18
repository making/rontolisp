# cdaadr

`(cdaadr pair)`

`(cdaadr x)` は `(cdr (car (car (cdr x))))` です。

```scheme
(cdaadr '(0 ((1 2 3)))) ; => (2 3)
```
