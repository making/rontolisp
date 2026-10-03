# reversible?

`(reversible? x)`

`clojure.core/reversible?`: ベクター・ソート済みマップ・ソート済みセット（`rseq` が受け取る値）なら `true`、リストとハッシュマップは `false` を返します。値としては1引数の関数です。

```clojure
(println (reversible? [1]) (reversible? (sorted-set 1)) (reversible? '(1)))  ; true true false
```
