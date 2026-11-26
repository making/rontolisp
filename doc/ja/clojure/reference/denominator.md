# denominator

`(denominator x)`

既約分数にした比の分母を返します。整数・double・`nil` はオラクル同様シグナルします。値としては1引数の関数です。

```clojure
(println (denominator 6/4) (denominator -3/6)) ; 2 2
```
