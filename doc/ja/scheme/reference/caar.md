# caar

`(caar pair)`

`(caar x)` は `(car (car x))` です。`car` と同様に、途中の空リストは `()` を返します。

```scheme
(caar '((1 2) 3)) ; => 1
```
