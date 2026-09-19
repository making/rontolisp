# read-bytevector

`(read-bytevector k [port])`

バイナリ入力ポート `port` から最大 `k` バイトを読み、新しいバイトベクタで返します。終わりではそれより少なく、1 バイトも残っていなければファイル終端オブジェクトを返します。`port` を省くと `read-u8` と同じくエラーです。

```scheme
(read-bytevector 2 (open-input-bytevector #u8(1 2 3))) ; => #u8(1 2)
```
