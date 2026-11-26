# close-output-port

`(close-output-port port)`

出力ポートに対する `close-port` です。それ以外のポートはエラーです。閉じた文字列ポートにも `get-output-string` は中身を返します。

```scheme
(let ((p (open-output-string))) (close-output-port p) (output-port-open? p)) ; => #f
```
