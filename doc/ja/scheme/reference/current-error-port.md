# current-error-port

`(current-error-port)`

現在のエラーポートを返します。`parameterize` が別のテキスト出力ポートを束縛していなければ標準エラー出力です。`current-error-port` は `current-output-port` と同じくパラメータオブジェクトです。

```scheme
(output-port? (current-error-port)) ; => #t
```
