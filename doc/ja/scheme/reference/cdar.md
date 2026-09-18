# cdar

`(cdar pair)`

`(cdar x)` は `(cdr (car x))` です。`car` と同様に、途中の空リストは `()` を返します。

```scheme
(cdar '((1 2) 3)) ; => (2)
```
