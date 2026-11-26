# with-precision

`(with-precision precision [:rounding mode] body...)`

本体の周囲で `*math-context*` を、評価された精度と丸めモード（`:rounding` が `RoundingMode`
の定数 `FLOOR` や `java.math.RoundingMode/FLOOR` を指定しない限り `HALF_UP`）の math context
に束縛し、最後の値を返します（空の本体では `nil`）。入れ子では束縛し直し、抜ければ根
（`nil`）に戻ります。10 進数の演算はオラクルと同じく各結果をその精度に丸めるため、割り
切れない商もシグナルせずに答えます。整数・比・double には影響しません。context は識別
ハッシュを除いたオラクルの `#object` として印字されます。値としては未知です
（`(def x with-precision)` は拒否されます）。

```clojure
(println (with-precision 10 (/ 1M 3)) (with-precision 10 (/ 1 3))) ; 0.3333333333M 1/3
(println (with-precision 3 :rounding FLOOR (/ 2M 3))) ; 0.666M
```
