# unchecked-multiply-int

`(unchecked-multiply-int a b)`

引数をintに変換し（doubleと比は切り捨て、32ビットの範囲外・`nil`・非数はシグナル）、積を32ビットに折り返して返すので、`(unchecked-multiply-int 65536 65536)` は `0` です。値としては2引数の関数です。

```clojure
(println (unchecked-multiply-int 65536 65536)) ; 0
```
