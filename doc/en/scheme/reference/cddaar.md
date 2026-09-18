# cddaar

`(cddaar pair)`

`(cddaar x)` is `(cdr (cdr (car (car x))))`.

```scheme
(cddaar '(((1 2 3 4)))) ; => (3 4)
```
