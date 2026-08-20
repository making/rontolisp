# torch:sgd

`(torch:sgd params &key lr momentum weight-decay)`

`params` (モジュール、またはパラメータテンソルのリスト) に対する確率的勾配降下法のオプティマイザ (PyTorch の `torch.optim.SGD`) を返します。`:lr` の既定値は `0.01`、`:momentum` と `:weight-decay` は `0` です。[`torch:step`](torch-step.md) は要素ごとに次を計算します。

```text
g   <- grad + weight-decay * param
buf <- momentum * buf + g          ; momentum が 0 でないときのみ
param <- param - lr * (momentum が 0 でなければ buf、そうでなければ g)
```

モーメンタムバッファはゼロから始まり、これは PyTorch の「初回はクローン」と同じ結果になります。ハイパーパラメータは普通のフィールドなので、学習率スケジュールは `(torch:set-field opt :lr new)` で書けます。

```lisp
(defparameter *p* (torch:parameter '(1.0 2.0)))
(defparameter *opt* (torch:sgd (list *p*) :lr 0.125))
(torch:backward (torch:sum (torch:mul *p* *p*)))
(torch:step *opt*)
(torch:data *p*)        ; => #f(0.75 1.5)
(torch:field *opt* :lr) ; => 0.125
```
