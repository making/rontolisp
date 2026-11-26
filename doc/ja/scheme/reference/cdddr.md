# cdddr

`(cdddr pair)`

`(cdddr x)` は `(cdr (cdr (cdr x)))`、つまり先頭の 3 要素を除いたリストです。

```scheme
(cdddr '(1 2 3 4)) ; => (4)
```
