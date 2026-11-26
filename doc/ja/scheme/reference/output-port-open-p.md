# output-port-open?

`(output-port-open? port)`

出力ポート `port` がまだ開いていれば `#t` を返します。出力ポートでないものはエラーです。

```scheme
(output-port-open? (open-output-string)) ; => #t
```
