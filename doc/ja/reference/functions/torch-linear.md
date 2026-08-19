# torch:linear

`(torch:linear in-features out-features &key bias)`

全結合レイヤー (PyTorch の `nn.Linear`) を返します。フィールド `:weight` は `(in-features out-features)` のパラメータ、`:bias` は `(out-features)` のパラメータ (`:bias nil` では `nil`) です。順伝播は `x . W (+ b)` なので、バイアスは先行するすべての軸にブロードキャストし、ランク 3 の入力はバッチごとに変換されます。

どちらのパラメータも PyTorch のデフォルトである `U(-1/sqrt(in-features), 1/sqrt(in-features))` から、シード可能な [`linalg:seed`](linalg-seed.md) の生成器で引かれます。シードを固定した実行はどのバックエンドでも再現します。重みは PyTorch の転置形 `(out in)` ではなく `(in out)` で保持するため、順伝播は素の [`torch:matmul`](torch-matmul.md) です。

```lisp
(defparameter *lin* (torch:linear 3 2))
(torch:set-field *lin* :weight (torch:parameter '((1.0 0.0) (0.0 1.0) (1.0 1.0))))
(torch:set-field *lin* :bias (torch:parameter '(0.5 -0.5)))
(torch:data (torch:forward *lin* (torch:tensor '((1.0 2.0 3.0)))))  ; => #d((4.5 4.5))
(torch:shape (torch:forward *lin* (torch:tensor (linalg:ones '(2 4 3))))) ; => (2 4 2)
```
