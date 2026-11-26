# double

`(double x)`

数値を倍精度浮動小数点数にして返します。文字・`nil` などの非数はオラクル同様シグナルします。
値としては1引数の関数です。

```clojure
(println (double 1) (double 1/2)) ; 1.0 0.5
```
