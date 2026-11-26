# unchecked-int

`(unchecked-int x)`

整数の下位32ビット（比は切り捨てた値）を符号付き整数として返すので、`(unchecked-int 3000000000)` は `-1294967296` です。文字はそのコードです。doubleは先に32ビットの範囲へ飽和させます（NaN は `0`）。`nil` と非数はシグナルします。値としては1引数の関数です。

```clojure
(println (unchecked-int 3000000000) (unchecked-int 2.7) (unchecked-int \a)) ; -1294967296 2 97
```
