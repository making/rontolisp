# torch:masked-fill

`(torch:masked-fill a mask value)`

微分可能なマスク埋め (`linalg:where` による `torch.masked_fill`) です。`mask` が非ゼロの位置ではスカラー `value`、ゼロの位置では `a` の要素になります。`mask` (0/1 配列、比較マスク、またはテンソル) と `value` は定数で勾配は流れず、`a` の勾配は埋められた位置でゼロです。[`torch:softmax`](torch-softmax.md) の前にアテンションスコアを `-infinity` で埋めるのがマスク付きアテンションのイディオムです。

```lisp
(torch:data (torch:masked-fill (torch:tensor '((1.0 2.0) (3.0 4.0)))
                               #2A((0 1) (0 0)) -1.0))
; => #d((1.0 -1.0) (3.0 4.0))
```
