# cddr

`(cddr pair)`

`(cddr x)` は `(cdr (cdr x))`、つまり先頭の 2 要素を除いたリストです。`car` と同様に、途中の空リストは `()` を返します。

```scheme
(cddr '(1 2 3)) ; => (3)
```
