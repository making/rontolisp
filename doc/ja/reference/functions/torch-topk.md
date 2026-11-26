# torch:topk

`(torch:topk a k &key axis indices)`

`axis` (既定は `-1`、最後の軸) に沿った上位 `k` 個の要素を、大きい順に並べて
返します。形状は `a` のその軸を `k` に狭めたもので、テンソルではなく生の linalg
配列です。[`torch:argmax`](torch-argmax.md) と同じく微分不可能です。

PyTorch の `torch.topk` は値とインデックスの組を返しますが、本パッケージの関数は
すべて単一値であるため、こちらはそのどちらか一方 — 既定では値、`:indices t` では
その位置 — を返します。同値の場合は最小のインデックスを採用するため、どの
バックエンドでも再現可能です (`torch.topk` の同値順序は未規定です)。

サンプリングループの top-`k` ステップは、これと
[`torch:masked-fill`](torch-masked-fill.md) の組み合わせです。各行の `k` 番目に
大きいロジット未満をすべて `-infinity` にすれば、softmax の重みがちょうど `0` に
なります。

```lisp
(torch:topk (linalg:from-list '((1.0 5.0 3.0) (9.0 2.0 8.0))) 2)
; => #d((5.0 3.0) (9.0 8.0))
(torch:topk (linalg:from-list '((1.0 5.0 3.0) (9.0 2.0 8.0))) 2 :indices t)
; => #d((1.0 2.0) (0.0 2.0))
```
