# numerator

`(numerator x)`

既約分数にした比の分子を返します（符号は分子に付きます）。整数・double・`nil` はオラクル同様シグナルします。値としては1引数の関数です。

```clojure
(println (numerator 6/4) (numerator -3/6)) ; 3 -1
```
