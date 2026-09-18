# cdaaar

`(cdaaar pair)`

`(cdaaar x)` is `(cdr (car (car (car x))))`.

```scheme
(cdaaar '((((1 2 3))))) ; => (2 3)
```
