# counted?

`(counted? x)`

`clojure.core/counted?`: リスト・ベクター・マップ・セット・レコードなら `true`、lazy seq・文字列・`nil` なら `false` を返します。strict な入力に対して操作が返す seq はリストなので、オラクルの lazy seq が `false` になる場面でも `true` です。値としては1引数の関数です。

```clojure
(println (counted? [1]) (counted? (lazy-seq [1])) (counted? "ab"))  ; true false false
```
