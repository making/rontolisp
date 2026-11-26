# cadddr

`(cadddr pair)`

`(cadddr x)` は `(car (cdr (cdr (cdr x))))`、つまりリストの 4 番目の要素です。

```scheme
(cadddr '(1 2 3 4 5)) ; => 4
```
