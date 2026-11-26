# peek-u8

`(peek-u8 [port])`

バイナリ入力ポート `port` の次のバイトを消費せずに返します。終わりではファイル終端オブジェクトを返します。`port` を省くと `read-u8` と同じくエラーです。

```scheme
(let ((p (open-input-bytevector #u8(9)))) (list (peek-u8 p) (read-u8 p))) ; => (9 9)
```
