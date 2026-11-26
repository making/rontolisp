# list-tail

`(list-tail list k)`

`list` の先頭 `k` 要素を除いた部分リストを返します。結果は `list` と構造を共有します。

仕様との差異: `k` が末尾を越えてもエラーにならず、結果は `()` です。

```scheme
(list-tail '(a b c d) 2) ; => (c d)
(list-tail '(1 2 3) 0) ; => (1 2 3)
```
