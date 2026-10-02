# nthnext

`(nthnext coll n)`

`coll` の seq の先頭 `n` 個より後を返します。何も残らなければ `nil` です。値としては
2引数の関数です。

```clojure
(println (nthnext [1 2 3] 1)) ; (2 3)
(println (nthnext [1 2 3] 3)) ; nil
```
