# number?

`(number? x)`

`clojure.core/number?`: 整数・分数・double のいずれかの数値なら `true` を返します。値としては1引数の関数です。

```clojure
(println (number? 1) (number? 1/2) (number? 1.5) (number? "1"))  ; true true true false
```
