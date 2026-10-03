# unchecked-subtract-int

`(unchecked-subtract-int a b)`

引数をintに変換し（doubleと比は切り捨て、32ビットの範囲外・`nil`・非数はシグナル）、差を32ビットに折り返して返すので、`(unchecked-subtract-int -2147483648 1)` は `2147483647` です。値としては2引数の関数です。

```clojure
(println (unchecked-subtract-int -2147483648 1)) ; 2147483647
```
