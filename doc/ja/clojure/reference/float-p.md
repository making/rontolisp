# float?

`(float? x)`

`clojure.core/float?`: 浮動小数点数なら `true` を返します。ここではすべて double です。値としては1引数の関数です。

```clojure
(println (float? 1.5) (float? 1/2))  ; true false
```
