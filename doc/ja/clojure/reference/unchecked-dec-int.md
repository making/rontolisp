# unchecked-dec-int

`(unchecked-dec-int x)`

引数をintに変換し（doubleと比は切り捨て、32ビットの範囲外・`nil`・非数はシグナル）、`x` から1を引いた値を32ビットに折り返して返すので、`(unchecked-dec-int -2147483648)` は `2147483647` です。値としては1引数の関数です。

```clojure
(println (unchecked-dec-int -2147483648)) ; 2147483647
```
