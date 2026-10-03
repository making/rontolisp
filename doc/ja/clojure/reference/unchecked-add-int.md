# unchecked-add-int

`(unchecked-add-int a b)`

引数をintに変換し（doubleと比は切り捨て、32ビットの範囲外・`nil`・非数はシグナル）、和を32ビットに折り返して返すので、`(unchecked-add-int 2147483647 1)` は `-2147483648` です。値としては2引数の関数です。

```clojure
(println (unchecked-add-int 2147483647 1)) ; -2147483648
```
