# associative?

`(associative? x)`

`clojure.core/associative?`: マップ・レコード・ベクターなら `true`、リストとセットは `false` を返します。値としては1引数の関数です。

```clojure
(println (associative? [1]) (associative? {}) (associative? '(1)) (associative? #{1}))  ; true true false false
```
