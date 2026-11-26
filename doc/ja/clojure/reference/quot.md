# quot

`(quot n d)`

0 へ切り捨てる整数除算です。`(quot -7 2)` は `-3` です。値としては同じ操作の上の 2 引数ラムダなので、`apply` と `map` は Clojure の引数順のまま裸で渡せます。

```clojure
(println (quot 7 2)) ; 3
(println (quot -7 2)) ; -3
(println (apply quot [7 2])) ; 3
```
