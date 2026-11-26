# cdaaar

`(cdaaar pair)`

`(cdaaar x)` は `(cdr (car (car (car x))))` です。

```scheme
(cdaaar '((((1 2 3))))) ; => (2 3)
```
