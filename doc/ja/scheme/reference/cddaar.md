# cddaar

`(cddaar pair)`

`(cddaar x)` は `(cdr (cdr (car (car x))))` です。

```scheme
(cddaar '(((1 2 3 4)))) ; => (3 4)
```
