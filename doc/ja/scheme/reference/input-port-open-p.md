# input-port-open?

`(input-port-open? port)`

入力ポート `port` がまだ開いていれば `#t` を返します。入力ポートでないものはエラーです。

```scheme
(let ((p (open-input-string "x"))) (close-port p) (input-port-open? p)) ; => #f
```
