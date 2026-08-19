# torch:padding-mask

`(torch:padding-mask tokens &key pad-id)`

`(batch length)` のトークン行列に対するパディングマスクを返します。`pad-id` (既定は `0`) を保持する位置が `1.0`、それ以外が `0.0` で、クエリ軸が挿入された `(batch 1 length)` の形になるため、アテンションスコアの `(batch query-length key-length)` にブロードキャストします。

結果はテンソルではなく**生の linalg 配列**です。マスクは定数であり、[`torch:masked-fill`](torch-masked-fill.md) も定数として受け取ります。[`torch:subsequent-mask`](torch-subsequent-mask.md) とは `linalg:add` や `linalg:maximum` で合成できます。0 でない値はすべてマスク扱いです。

```lisp
(defparameter *tokens* (torch:pad-sequence '((1 2 3) (4 5))))
(torch:padding-mask *tokens*)                ; => #d(((0.0 0.0 0.0)) ((0.0 0.0 1.0)))
(linalg:shape (torch:padding-mask *tokens*)) ; => (2 1 3)
```
