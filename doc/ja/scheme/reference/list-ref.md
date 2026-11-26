# list-ref

`(list-ref list k)`

`list` の `k` 番目（0 始まり）の要素を返します。

仕様との差異: `k` が末尾を越えてもエラーにならず、結果は `()` です。

```scheme
(list-ref '(a b c d) 2) ; => c
(list-ref '(a b c d) 0) ; => a
```
