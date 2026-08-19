# linalg:log-softmax

`(linalg:log-softmax array &key axis)`

[`linalg:softmax`](linalg-softmax.md) の対数を返します。softmax の対数を取るのではなく `(x - max) - log(sum(exp(x - max)))` として計算するため、重みがちょうど 0 の場合に `NaN` ではなく `-infinity` になります。`:axis` の規則は `linalg:softmax` と同じです。これはクロスエントロピー損失の数値的に安定な半分です。

```lisp
(linalg:log-softmax #(0 0))                   ; => #d(-0.6931471805599453 -0.6931471805599453)
(linalg:log-softmax #2A((0 0) (1 1)) :axis 1) ; => #d((-0.6931471805599453 -0.6931471805599453) (-0.6931471805599453 -0.6931471805599453))
```
