# sequential?

`(sequential? x)`

`clojure.core/sequential?`: リスト・lazy seq・ベクターなら `true`、マップ・セット・文字列・`nil` なら `false` を返します。値としては1引数の関数です。

```clojure
(println (sequential? [1]) (sequential? '(1)))  ; true true
(println (sequential? #{1}) (sequential? "ab")) ; false false
```
