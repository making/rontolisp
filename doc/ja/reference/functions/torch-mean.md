# torch:mean

`(torch:mean a &key axis keepdims)`

微分可能な算術平均 (`linalg:mean`、`:axis` / `:keepdims` の規則は [`torch:sum`](torch-sum.md) と同じ) です。backward は合計の随伴を縮約した要素数で割ったものです。[`torch:sub`](torch-sub.md) の二乗の平均が MSE 損失になります。

```lisp
(torch:item (torch:mean (torch:tensor '(1.0 2.0 3.0)))) ; => 2.0
```
