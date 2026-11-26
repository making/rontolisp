# unchecked-byte

`(unchecked-byte x)`

整数の下位8ビット（比は切り捨てた値）を符号付き整数として返すので、`(unchecked-byte 200)` は `-56` です。doubleは32ビットの範囲へ飽和させてから折り返します。文字・`nil`・非数はシグナルします。値としては1引数の関数です。

```clojure
(println (unchecked-byte 200) (unchecked-byte -129)) ; -56 127
```
