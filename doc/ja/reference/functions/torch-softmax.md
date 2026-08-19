# torch:softmax

`(torch:softmax a &key axis)`

微分可能な最大値差し引き softmax (`linalg:softmax`) です。`:axis` なしではテンソル全体が 1 つの分布、整数の `:axis` を渡すとスライスごとに 1 分布 -- torch の `softmax(x, dim)`、アテンション重みの形です。backward は各分布上の `s * (g - sum(g * s))` です。[`torch:masked-fill`](torch-masked-fill.md) で `-infinity` に埋めたマスク位置はちょうど `0.0` になります。

```lisp
(torch:data (torch:softmax (torch:tensor '(1.0 1.0 1.0 1.0))))          ; => #d(0.25 0.25 0.25 0.25)
(torch:data (torch:softmax (torch:tensor '((0.0 0.0) (1.0 1.0))) :axis 1)) ; => #d((0.5 0.5) (0.5 0.5))
```
