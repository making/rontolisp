# torch:cross-entropy-loss

`(torch:cross-entropy-loss logits targets &key ignore-index reduction)`

生の**ロジット**と整数のクラスターゲットに対する交差エントロピーをスカラーテンソルとして返します (PyTorch の `nn.CrossEntropyLoss`)。ロジットの形は `(... num-classes)` で、先行する軸は平坦化されるため `(batch seq vocab)` がそのまま使えます。targets は対応する先行軸の形を取ります。計算は数値的に安定な形である `-log-softmax` のターゲットクラス位置の取り出しで行うため、softmax の出力を渡しては**いけません**。

`:ignore-index k` はターゲットが `k` の位置を総和からも平均の分母からも除きます。パディング位置を寄与させないための指定です。`:reduction :sum` は平均ではなく総和、`:reduction :none` は位置ごとのテンソルを返します。

```lisp
(torch:item (torch:cross-entropy-loss (torch:tensor '((0.0 0.0))) #(0)))
; => 0.6931471805599453
(torch:item (torch:cross-entropy-loss (torch:tensor '((0.0 0.0) (0.0 0.0)))
                                      #(0 1) :ignore-index 1))
; => 0.6931471805599453
```
