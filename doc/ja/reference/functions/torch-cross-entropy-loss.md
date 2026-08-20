# torch:cross-entropy-loss

`(torch:cross-entropy-loss logits targets &key ignore-index reduction)`

生の**ロジット**に対する交差エントロピーをスカラーテンソルとして返します (PyTorch の `nn.CrossEntropyLoss`)。ロジットの形は `(... num-classes)` で、先行する軸は平坦化されるため `(batch seq vocab)` がそのまま使えます。計算は数値的に安定な形である `-log-softmax` から行うため、softmax の出力を渡しては**いけません**。

ターゲットは 2 通りに解釈されます。

- 対応する先行軸の形を持つ**クラスインデックス** (数値、リスト、インデックスベクタ、テンソル)。損失は `-log-softmax` のターゲットクラス位置の取り出しです。
- ロジットと同じ形のテンソルまたは配列で与える**クラス確率** (PyTorch のソフトラベル形式)。損失は位置ごとの `-sum(target * log-softmax(logits))` で、ターゲット側が勾配を要求していればそこにも勾配が流れます。リストは常にクラスインデックスとして読むため、確率で渡すにはテンソルか配列が必要です。

`:ignore-index k` はクラスインデックスのターゲットが `k` の位置を総和からも平均の分母からも除きます。パディング位置を寄与させないための指定で、PyTorch と同じく確率ターゲットには適用されません。`:reduction :sum` は平均ではなく総和、`:reduction :none` は位置ごとのテンソルを返します。

```lisp
(torch:item (torch:cross-entropy-loss (torch:tensor '((0.0 0.0))) #(0)))
; => 0.6931471824645996
(torch:item (torch:cross-entropy-loss (torch:tensor '((0.0 0.0) (0.0 0.0)))
                                      #(0 1) :ignore-index 1))
; => 0.6931471824645996
(torch:item (torch:cross-entropy-loss (torch:tensor '((0.0 0.0)))
                                      (torch:tensor '((0.5 0.5)))))
; => 0.6931471824645996
```
