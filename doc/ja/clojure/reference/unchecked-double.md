# unchecked-double

`(unchecked-double x)`

`double` と同じく、数値を倍精度浮動小数点数にして返します。文字・`nil`・その他の非数はシグナルします。値としては1引数の関数です。

```clojure
(println (unchecked-double 1) (unchecked-double 1/2)) ; 1.0 0.5
```
