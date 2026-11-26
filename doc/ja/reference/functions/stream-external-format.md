# stream-external-format

`(stream-external-format stream)`

ストリームが読み書きする外部フォーマット。常に `:utf-8` です。リーダーもすべてのライターも UTF-8 だけを使うため、`open` の `:external-format` オプションに他の選択肢はありません。

```lisp
(with-output-to-string (s) (princ (stream-external-format s) s)) ; => "UTF-8"
```
