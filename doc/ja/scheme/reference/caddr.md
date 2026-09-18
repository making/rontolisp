# caddr

`(caddr pair)`

`(caddr x)` は `(car (cdr (cdr x)))`、つまりリストの 3 番目の要素です。

```scheme
(caddr '(1 2 3 4)) ; => 3
```
