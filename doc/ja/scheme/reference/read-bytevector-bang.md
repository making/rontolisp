# read-bytevector!

`(read-bytevector! bytevector [port [start [end]]])`

バイナリ入力ポート `port` からバイトを読み、`bytevector` の添字 `start` から `end` の手前までに格納して、読んだバイト数を返します。1 バイトも残っていなければファイル終端オブジェクトを返します。`port` を省くと `read-u8` と同じくエラーです。

```scheme
(let ((b (make-bytevector 4 0))) (read-bytevector! b (open-input-bytevector #u8(7 8)) 1) b) ; => #u8(0 7 8 0)
```
