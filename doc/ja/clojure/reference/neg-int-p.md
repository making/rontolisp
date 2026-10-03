# neg-int?

`(neg-int? x)`

`clojure.core/neg-int?`: 0 より小さい `int?` なら `true` を返します。値としては1引数の関数です。

```clojure
(println (neg-int? -1) (neg-int? -1/2))  ; true false
```
