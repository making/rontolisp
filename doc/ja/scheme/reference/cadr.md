# cadr

`(cadr pair)`

`(cadr x)` は `(car (cdr x))`、つまりリストの 2 番目の要素です。`car` と同様に、途中の空リストは `()` を返します。

```scheme
(cadr '(1 2 3)) ; => 2
```
