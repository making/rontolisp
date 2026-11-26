# unchecked-negate-int

`(unchecked-negate-int x)`

引数をintに変換し（doubleと比は切り捨て、32ビットの範囲外・`nil`・非数はシグナル）、`x` の符号を反転した値を32ビットに折り返して返すので、`(unchecked-negate-int -2147483648)` は `-2147483648` です。値としては1引数の関数です。

```clojure
(println (unchecked-negate-int -2147483648)) ; -2147483648
```
