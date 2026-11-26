# torch:mse-loss

`(torch:mse-loss input target &key reduction)`

input と target の平均二乗誤差をスカラーテンソルとして返します (PyTorch の `nn.MSELoss`)。`:reduction :sum` は平均ではなく総和、`:reduction :none` は要素ごとのテンソルを返します。target 自体が勾配を要求するテンソルでない限り定数として扱われます。どちらの引数も数値・リスト・配列で構いません。

```lisp
(torch:item (torch:mse-loss (torch:tensor '(1.0 2.0)) '(0.0 0.0)))                 ; => 2.5
(torch:item (torch:mse-loss (torch:tensor '(1.0 2.0)) '(0.0 0.0) :reduction :sum)) ; => 5.0
```
