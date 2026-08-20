# torch:adamw

`(torch:adamw params &key lr betas eps weight-decay)`

`params` (モジュール、またはパラメータテンソルのリスト) に対する AdamW
オプティマイザ (PyTorch の `torch.optim.AdamW`) を返します。
[`torch:adam`](torch-adam.md) と同じ規則ですが、重み減衰が分離 (decoupled) されて
います。減衰項が勾配に入って適応的な分母で再スケールされるのではなく、Adam の
更新の前にパラメータ自身が縮みます。要素ごとに
[`torch:step`](torch-step.md) は次を計算します。

```text
param <- param - lr * weight-decay * param
m <- beta1 * m + (1 - beta1) * grad
v <- beta2 * v + (1 - beta2) * grad^2
param <- param - lr * (m / (1 - beta1^t)) / (sqrt(v / (1 - beta2^t)) + eps)
```

`:lr` の既定は `0.001`、`:betas` は `(0.9 0.999)`、`:eps` は `1.0e-8`、
`:weight-decay` は `0.01` です (PyTorch の既定。`torch:adam` は `0`)。

Transformer の学習に使われるのはこの規則です。減衰させてはならないパラメータ
(バイアス、LayerNorm のゲイン、埋め込みテーブル) は、`:weight-decay 0.0` で作った
2 つめのオプティマイザに入れます。互いに素なパラメータリストに対する 2 つの
オプティマイザが、ここでの `torch.optim` のパラメータグループにあたります。

```lisp
(defparameter *w* (torch:parameter '(1.0)))
(defparameter *opt* (torch:adamw (list *w*) :lr 0.1 :weight-decay 0.5))
(torch:backward (torch:sum (torch:mul *w* *w*)))
(torch:step *opt*)
(< (abs (- (torch:item *w*) 0.85)) 1.0e-8) ; => T
```
