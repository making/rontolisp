# close-port

`(close-port port)`

`port` を閉じます。その後の読み書きはエラーです（`write: the port is closed: #<textual-output-port>`）。閉じたポートを閉じても何も起きません。ファイルポートを閉じるとファイルが閉じられ、バッファに残っていた内容が書き出されます。標準のポートを閉じても、ポートオブジェクトが閉じた印を持つだけです。

```scheme
(let ((p (open-output-string))) (close-port p) (output-port-open? p)) ; => #f
```
