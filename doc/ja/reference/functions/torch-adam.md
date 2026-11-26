# torch:adam

`(torch:adam params &key lr betas eps)`

`params` (モジュール、またはパラメータテンソルのリスト) に対する Adam オプティマイザ (PyTorch の `torch.optim.Adam`) を返します。`:lr` の既定値は `0.001`、`:betas` は `(0.9 0.999)` (PyTorch の `(beta1, beta2)` タプルを 2 要素のリストで表したもの)、`:eps` は `1.0e-8` です。[`torch:step`](torch-step.md) は要素ごとに次を計算します。

```text
m <- beta1 * m + (1 - beta1) * grad
v <- beta2 * v + (1 - beta2) * grad^2
param <- param - lr * (m / (1 - beta1^t)) / (sqrt(v / (1 - beta2^t)) + eps)
```

`t` はオプティマイザ自身の [`torch:step-count`](torch-step-count.md) で、最初のステップでは `1` です。したがって初回の更新も完全にバイアス補正され、その大きさは `lr * (1 - beta1)` ではなく `lr` になります。

```lisp
(defparameter *w* (torch:parameter '(1.0)))
(defparameter *opt* (torch:adam (list *w*) :lr 0.125))
(torch:backward (torch:sum (torch:mul *w* *w*)))
(torch:step *opt*)
(torch:step-count *opt*)                    ; => 1
(< (abs (- (torch:item *w*) 0.875)) 1.0e-8) ; => T
```
