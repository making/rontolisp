# unchecked-float

`(unchecked-float x)`

数値を倍精度浮動小数点数にして返します。単精度の値は持ちません（`float` を参照）。float の範囲を超える値は、オラクル同様その符号の無限大です。文字・`nil`・その他の非数はシグナルします。値としては1引数の関数です。

```clojure
(println (unchecked-float 1) (unchecked-float 1e300)) ; 1.0 ##Inf
```
