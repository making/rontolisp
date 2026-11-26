# torch:pad-sequence

`(torch:pad-sequence sequences &key padding-value)`

可変長のシーケンス (リスト、インデックスベクトル、テンソル) のリストを、パディング済みのランク 2 テンソル 1 つ、すなわち**バッチ先頭**の `(batch 最長)` として返します。各行は最長のものに合わせて `padding-value` (既定は `0`) で埋められます。`batch_first=True` を指定した `torch.nn.utils.rnn.pad_sequence` に相当します。

結果はトークンインデックスの定数テンソルで、[`torch:embedding`](torch-embedding.md) や [`torch:padding-mask`](torch-padding-mask.md) にそのまま渡せます。

```lisp
(torch:data (torch:pad-sequence '((1 2 3) (4 5) (6))))
; => #f((1.0 2.0 3.0) (4.0 5.0 0.0) (6.0 0.0 0.0))
(torch:shape (torch:pad-sequence '((1 2) (3)) :padding-value 9)) ; => (2 2)
```
