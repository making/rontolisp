# indexed?

`(indexed? x)`

`clojure.core/indexed?`: ベクターなら `true`、リストと文字列は `false` を返します。値としては1引数の関数です。

```clojure
(println (indexed? [1]) (indexed? '(1)) (indexed? "ab"))  ; true false false
```
