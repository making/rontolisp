# cddddr

`(cddddr pair)`

`(cddddr x)` は `(cdr (cdr (cdr (cdr x))))`、つまり先頭の 4 要素を除いたリストです。

```scheme
(cddddr '(1 2 3 4 5 6)) ; => (5 6)
```
