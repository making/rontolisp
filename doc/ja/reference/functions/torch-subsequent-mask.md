# torch:subsequent-mask

`(torch:subsequent-mask sequence-length)`

系列の因果 (先読み禁止) マスクを返します。対角より厳密に**上**が `1.0` で、形は `(1 sequence-length sequence-length)` なのでバッチ方向にブロードキャストします。位置 `i` は `j > i` のいずれにも注意を向けられません。

[`torch:padding-mask`](torch-padding-mask.md) と同じく生の linalg 配列です。[`torch:softmax`](torch-softmax.md) の前にマスク位置を `-infinity` で埋めるのがマスク付きアテンションの定石で、マスクされた重みはちょうど `0.0` になります。

```lisp
(torch:subsequent-mask 3) ; => #d(((0.0 1.0 1.0) (0.0 0.0 1.0) (0.0 0.0 0.0)))
(defparameter *scores* (torch:tensor (linalg:ones '(1 2 2))))
(torch:data (torch:softmax (torch:masked-fill *scores* (torch:subsequent-mask 2) (/ -1.0 0.0))
                           :axis -1))
; => #f(((1.0 0.0) (0.5 0.5)))
```
