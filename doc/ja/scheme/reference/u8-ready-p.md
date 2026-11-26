# u8-ready?

`(u8-ready? [port])`

バイナリ入力ポート `port` からブロックせずにバイトを読めるなら `#t` を返します。バイトベクタポートはブロックしないので常に `#t` です。`port` を省くと `read-u8` と同じくエラーです。

```scheme
(u8-ready? (open-input-bytevector #u8())) ; => #t
```
