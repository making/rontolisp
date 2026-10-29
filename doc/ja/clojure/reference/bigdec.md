# bigdec

`(bigdec x)`

10 進数（オラクルの `BigDecimal`）を返します。10 進数はそのまま、整数はスケール 0、double は表示される最短の 10 進表現（`(bigdec 0.1)` は `0.1M`、`(bigdec 100.0)` は `100.0M`）、比は分子を分母で割った値（`(bigdec 1/3)` は `with-precision` で丸めない限りオラクル同様シグナル）、文字列は `new BigDecimal` と同じく読んだ値です（`"1.50"`、`"1e3"`。前後の空白はシグナル）。NaN・無限大・`nil`・文字はシグナルします。値としては1引数の関数です。

```clojure
(println (bigdec 0.1) (bigdec "1.50") (bigdec 1/8) (bigdec 3)) ; 0.1M 1.50M 0.125M 3M
```
