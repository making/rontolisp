# comparator

`(comparator pred)`

`clojure.core/comparator`: `(pred a b)` が真なら `-1`、そうでなく `(pred b a)` が真なら `1`、
どちらでもなければ `0` を返す2引数関数を返します。`<` や `>` のような述語を、`sort`・
`sort-by`・`sorted-map-by`・`sorted-set-by` が受け取る比較関数にします。値としては
1引数の関数です。

```clojure
(prn (sort (comparator >) [1 3 2]))           ; (3 2 1)
(prn ((comparator <) 1 2) ((comparator <) 1 1)) ; -1 0
```
