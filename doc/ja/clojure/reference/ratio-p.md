# ratio?

`(ratio? x)`

`clojure.core/ratio?`: 分数（整数でない有理数）なら `true`、10 進数（`1.5M`）なら `false` を返します。オラクルと同じです。値としては1引数の関数です。

```clojure
(println (ratio? 1/2) (ratio? 2) (ratio? 0.5) (ratio? 1.5M))  ; true false false false
```
