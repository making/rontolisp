# return-from

`(return-from name [value])`

囲んでいる関数から `value` (デフォルト `nil`) を返し、実行を早期終了します。ブロック `name` は無視されます — 名前付きブロックは存在しないため、`defun`/`lambda` 本体の中の `return-from` はすべてその関数自体から抜けます。Common Lisp からの逸脱: `do`/`loop` の内側にネストした `return-from` は関数ではなくそのループ (最も近いブロック) から抜けます。これはループが関数の最後のフォームである場合にのみ等価です。`block` 自体は未サポートです。

```lisp
(defun classify (n)
  (when (= n 0)
    (return-from classify :zero))
  (* n 10))
(classify 0) ; => :zero
```
