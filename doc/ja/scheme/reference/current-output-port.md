# current-output-port

`(current-output-port)`

現在の出力ポートを返します。`parameterize` が別のテキスト出力ポートを束縛していなければ標準出力です。`current-output-port` はパラメータオブジェクトなので、`(parameterize ((current-output-port port)) body ...)` は `body` がポート引数なしで書き出すものをすべて `port` に送ります。束縛は `body` をどう抜けても元に戻ります。

```scheme
(let ((p (open-output-string))) (parameterize ((current-output-port p)) (display "hi")) (get-output-string p)) ; => "hi"
```
