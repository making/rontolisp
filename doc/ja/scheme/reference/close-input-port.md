# close-input-port

`(close-input-port port)`

入力ポートに対する `close-port` です。それ以外のポートはエラーです。

```scheme
(let ((p (open-input-string "x"))) (close-input-port p) (input-port-open? p)) ; => #f
```
