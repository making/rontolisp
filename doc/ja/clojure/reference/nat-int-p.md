# nat-int?

`(nat-int? x)`

`clojure.core/nat-int?`: 負でない `int?` なら `true` を返します。値としては1引数の関数です。

```clojure
(println (nat-int? 0) (nat-int? -1) (nat-int? 1.0))  ; true false false
```
