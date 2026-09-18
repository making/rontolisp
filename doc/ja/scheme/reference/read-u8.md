# read-u8

`(read-u8 [port])`

バイナリ入力ポート `port` から次のバイトを読んで返します。終わりではファイル終端オブジェクトを返します。標準のポートはテキストポートなので、`port` を省くとエラーになります（Gauche は標準入力のバイトを読みます）。

```scheme
(read-u8 (open-input-bytevector #u8(1 2))) ; => 1
```
