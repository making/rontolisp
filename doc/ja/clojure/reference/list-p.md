# list?

`(list? x)`

`clojure.core/list?`: リストなら `true` を返します。strict な入力に対して操作が返す seq（`map`・`filter`・`range`・ベクターの `seq` など）はここではリストなので、オラクルの lazy seq や chunked seq が `false` を返す場面で `true` を返します。lazy seq はどちらでも `false` です。`nil` が空リストなので `(list? ())` は `false` です。値としては1引数の関数です。

```clojure
(println (list? '(1)) (list? [1]) (list? (lazy-seq [1])))  ; true false false
```
