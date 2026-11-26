# associative?

`(associative? x)`

`clojure.core/associative?`: マップ（ソート済みのものも含む）・レコード・ベクターなら `true`、リストとセット（ソート済みのものも含む）は `false` を返します。値としては1引数の関数です。

```clojure
(println (associative? [1]) (associative? {}) (associative? '(1)) (associative? #{1}))  ; true true false false
```
