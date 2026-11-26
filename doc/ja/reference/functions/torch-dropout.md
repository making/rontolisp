# torch:dropout

`(torch:dropout p)`

ドロップ確率 p のドロップアウトレイヤー (PyTorch の `nn.Dropout`) を返します。p は単一のフィールド `:p` に入ります。**学習**モードでは各要素を確率 p で 0 にし、生き残った要素を `1 / (1 - p)` 倍します (期待値が変わらない inverted dropout)。**評価**モード ([`torch:eval`](torch-eval.md)) では恒等写像で、`p` が 0 のときも同様です。マスクはシード可能な [`linalg:seed`](linalg-seed.md) の生成器から得るため、シードを固定した学習はどのバックエンドでも再現します。

```lisp
(defparameter *drop* (torch:dropout 0.5))
(torch:data (torch:forward (torch:eval *drop*) (torch:tensor '(1.0 2.0)))) ; => #f(1.0 2.0)
(torch:data (torch:forward (torch:dropout 0) (torch:tensor '(1.0 2.0))))   ; => #f(1.0 2.0)
```
