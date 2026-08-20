# torch:slice

`(torch:slice a specs)`

微分可能な numpy の基本スライス (`linalg:slice`: 軸ごとに 1 つの指定 -- `nil` は軸全体、`(start end)` / `(start end step)` はその軸に沿った選択で、負のインデックスやステップも使えます) です。backward はスライスが読んだ位置へ、ゼロの配列に勾配を散布して戻します。

```lisp
(torch:data (torch:slice (torch:tensor '((0.0 1.0 2.0) (3.0 4.0 5.0))) '(nil (0 2))))
; => #f((0.0 1.0) (3.0 4.0))
```
