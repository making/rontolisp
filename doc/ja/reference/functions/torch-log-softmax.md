# torch:log-softmax

`(torch:log-softmax a &key axis)`

微分可能な log-softmax (`linalg:log-softmax`、交差エントロピー損失の数値的に安定な半分) です。[`torch:softmax`](torch-softmax.md) の `log` ではなく `(x - max) - log(sum(exp(x - max)))` として計算します。backward は `g - softmax(x) * sum(g)` です。

```lisp
(torch:data (torch:log-softmax (torch:tensor '(0.0 0.0))))
; => #f(-0.6931472 -0.6931472)
```
