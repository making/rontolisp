# integer?

`(integer? x)`

`clojure.core/integer?`: 大きさによらず整数なら `true`、10 進数（`1M`）なら `false` を返します。オラクルと同じです。値としては1引数の関数です。

```clojure
(println (integer? 1) (integer? 99999999999999999999) (integer? 1.0) (integer? 1M))  ; true true false false
```
