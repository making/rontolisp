# torch:clip-grad-norm

`(torch:clip-grad-norm params max-norm)`

`params` (モジュール、オプティマイザのパラメータリスト、またはテンソルのリスト)
に対する勾配ノルムのクリッピング (PyTorch の
`torch.nn.utils.clip_grad_norm_`) です。

すべての勾配を 1 本の長いベクトルとみなしたときの L2 ノルムを返します。
クリップ前に測定した値なので、学習ループでそのままログに出せます。そのノルムが
`max-norm` を超えた場合、すべての勾配が `max-norm / (norm + 1e-6)` (PyTorch と
同じ分母) でその場でスケールされます。超えない場合は何も変更しません。勾配が
届いていないパラメータはスキップされます。

呼ぶ位置は [`torch:backward`](torch-backward.md) と
[`torch:step`](torch-step.md) の間です。オプティマイザがこれから読む勾配を
書き換えるだけで、テープには触れません。

```lisp
(defparameter *w* (torch:parameter '(3.0 4.0)))
(torch:backward (torch:sum (torch:mul *w* *w*)))   ; 勾配は (6 8)、ノルムは 10
(< (abs (- (torch:clip-grad-norm (list *w*) 1.0) 10.0)) 1.0e-9)  ; => T
(< (abs (- (aref (torch:grad *w*) 0) 0.6)) 1.0e-6)               ; => T
```
