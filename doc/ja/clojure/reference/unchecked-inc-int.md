# unchecked-inc-int

`(unchecked-inc-int x)`

引数をintに変換し（doubleと比は切り捨て、32ビットの範囲外・`nil`・非数はシグナル）、`x` に1を足した値を32ビットに折り返して返すので、`(unchecked-inc-int 2147483647)` は `-2147483648` です。値としては1引数の関数です。

```clojure
(println (unchecked-inc-int 2147483647)) ; -2147483648
```
