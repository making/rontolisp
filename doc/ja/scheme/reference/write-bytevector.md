# write-bytevector

`(write-bytevector bytevector [port [start [end]]])`

`bytevector` の添字 `start` から `end` の手前までのバイトをバイナリ出力ポート `port` に書き出します。`port` を省くと `write-u8` と同じくエラーです。

```scheme
(let ((p (open-output-bytevector))) (write-bytevector #u8(1 2 3 4) p 1 3) (get-output-bytevector p)) ; => #u8(2 3)
```
