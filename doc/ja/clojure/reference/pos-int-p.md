# pos-int?

`(pos-int? x)`

`clojure.core/pos-int?`: 0 より大きい `int?` なら `true` を返します。値としては1引数の関数です。

```clojure
(println (pos-int? 1) (pos-int? 0))  ; true false
```
