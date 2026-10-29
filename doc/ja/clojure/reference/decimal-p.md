# decimal?

`(decimal? x)`

`clojure.core/decimal?`: 10 進数（`M` リテラル、`bigdec` の結果、その読み取り。オラクルの `BigDecimal`）なら `true`、それ以外（double と比を含む）なら `false` を返します。値としては1引数の関数です。

```clojure
(println (decimal? 1.5M) (decimal? (bigdec "2")) (decimal? 1.5) (decimal? 3/2))  ; true true false false
```
