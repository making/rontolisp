# torch:optimizer-kind

`(torch:optimizer-kind optimizer)`

[`torch:optimizer`](torch-optimizer.md) に渡された種別キーワードを返します。[`torch:sgd`](torch-sgd.md) なら `:sgd`、[`torch:adam`](torch-adam.md) なら `:adam` です。引数がオプティマイザでなければエラーを通知します。

```lisp
(torch:optimizer-kind (torch:sgd nil))  ; => :SGD
(torch:optimizer-kind (torch:adam nil)) ; => :ADAM
```
