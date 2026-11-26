# unchecked-short

`(unchecked-short x)`

整数の下位16ビット（比は切り捨てた値）を符号付き整数として返すので、`(unchecked-short 70000)` は `4464` です。doubleは32ビットの範囲へ飽和させてから折り返します。文字・`nil`・非数はシグナルします。値としては1引数の関数です。

```clojure
(println (unchecked-short 70000) (unchecked-short 32768)) ; 4464 -32768
```
