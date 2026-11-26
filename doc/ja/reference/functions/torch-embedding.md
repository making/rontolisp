# torch:embedding

`(torch:embedding num-embeddings embedding-dim)`

埋め込みテーブル (PyTorch の `nn.Embedding`) を返します。フィールドは `:weight` ひとつで、PyTorch のデフォルトと同じく標準正規分布から引かれた `(num-embeddings embedding-dim)` のパラメータです。順伝播は**任意**の形の整数インデックスを取り、埋め込み軸を末尾に付けて返します。同じ行が 2 回選ばれた場合は両方の勾配が蓄積されます ([`torch:index-select`](torch-index-select.md) の随伴)。

```lisp
(defparameter *emb* (torch:embedding 4 2))
(torch:set-field *emb* :weight
                 (torch:parameter '((0.0 1.0) (2.0 3.0) (4.0 5.0) (6.0 7.0))))
(torch:data (torch:forward *emb* #(2 0)))            ; => #f((4.0 5.0) (0.0 1.0))
(torch:shape (torch:forward *emb* #2A((1 2) (3 0)))) ; => (2 2 2)
```
