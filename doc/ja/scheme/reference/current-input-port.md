# current-input-port

`(current-input-port)`

現在の入力ポートを返します。`parameterize` が別のテキスト入力ポートを束縛していなければ標準入力です。`current-input-port` はパラメータオブジェクトなので、`(parameterize ((current-input-port port)) body ...)` は `body` の動的範囲のあいだ `read`、`read-char`、`peek-char`、`read-line`、`read-string` に `port` を読ませます。

```scheme
(parameterize ((current-input-port (open-input-string "(1 2) x"))) (read)) ; => (1 2)
```
