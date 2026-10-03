# reversible?

`(reversible? x)`

`clojure.core/reversible?`: ベクター（`rseq` が受け取る値）なら `true`、リストは `false` を返します。値としては1引数の関数です。

```clojure
(println (reversible? [1]) (reversible? '(1)))  ; true false
```
