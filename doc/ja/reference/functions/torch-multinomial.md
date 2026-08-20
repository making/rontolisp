# torch:multinomial

`(torch:multinomial probs &key num-samples replacement)`

`probs` の各行 (最後の軸が重み) から `num-samples` 個のインデックスを抽出します
(PyTorch の `torch.multinomial`)。重みの合計は `1` である必要はなく行ごとに
正規化されますが、非負でなければなりません。ランク 1 の入力には
`(num-samples)` のインデックス配列を、ランク n の入力には最後の軸を
`num-samples` に置き換えた形状を返します。微分不可能で、テンソルではなく生の
linalg 配列です。

`:replacement t` を指定しない場合、同じ行で既に引いたインデックスは再び引かれ
ません (PyTorch の既定)。このとき `num-samples` は重みの個数以下である必要が
あります。抽出はシード付きの [`linalg:seed`](linalg-seed.md) 生成器から行われる
ため、サンプリングはどのバックエンドでも再現します。

```lisp
(linalg:seed 3)
(torch:multinomial (linalg:from-list '((0.0 1.0 0.0) (0.0 0.0 1.0))))
; => #d((1.0) (2.0))
```
