# inexact->exact

`(inexact->exact z)`

`exact` の R5RS での名前で、`z` と等しい正確な数を返します。浮動小数点数は 2 進表現の正確な値に変換されます。`(scheme r5rs)` に属し、`import` のないファイルからだけ見えます。

```scheme
(inexact->exact 0.25) ; => 1/4
(inexact->exact 2.0) ; => 2
```
