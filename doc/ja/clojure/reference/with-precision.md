# with-precision

`(with-precision precision body...)`

本体の周囲で `*math-context*` を評価された精度に束縛し、最後の値を返します（空の本体
では `nil`）。入れ子では束縛し直し、抜ければ根に戻ります（根は `nil`、oracle は既定の
コンテキストを束縛します）。除算はここでは正確なままです -- oracle の `BigDecimal`
ではなく有理数 -- ので、この束縛は丸めを何もせず動的範囲を区切るだけです。`1/3` は
精度 10 での oracle の `0.3333333333M` に、`1/2` は `0.5M` に対応します。値としては
未知です（`(def x with-precision)` は拒否されます）。

```clojure
(println (with-precision 10 (/ 1 3))) ; 1/3
(println (with-precision 10 *math-context*)) ; 10
```
