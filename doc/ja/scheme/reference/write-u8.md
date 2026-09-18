# write-u8

`(write-u8 byte [port])`

`byte` をバイナリ出力ポート `port` に書き出します。0〜255 の外の `byte` はエラーです。標準のポートはテキストポートなので、`port` を省くとエラーになります（Gauche は標準出力にバイトを書きます）。

```scheme
(let ((p (open-output-bytevector))) (write-u8 255 p) (get-output-bytevector p)) ; => #u8(255)
```
