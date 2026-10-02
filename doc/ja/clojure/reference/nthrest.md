# nthrest

`(nthrest coll n)`

`n` が正でなければ `coll` そのものを、そうでなければ先頭 `n` 個より後の rest を返します。
使い切ると `nil` です（オラクルは `()`）。値としては2引数の関数です。

```clojure
(prn (nthrest [1 2 3] 0)) ; [1 2 3]
(prn (nthrest [1 2 3] 1)) ; (2 3)
```
