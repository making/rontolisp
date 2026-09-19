# open-binary-output-file

`(open-binary-output-file string)`

`string` という名前のファイルを作り（既にあれば空にし）、そのバイトを書くバイナリ出力ポートを返します。作れないファイルは、`file-error?` が `#t` を返すエラーを raise します。[open-output-file](open-output-file.md) と同じく、`close-port` で閉じてください。

```scheme
(define p (open-binary-output-file "bytes.bin"))
(write-bytevector #u8(1 2 255) p)
(close-port p)
(call-with-port (open-binary-input-file "bytes.bin") (lambda (in) (read-bytevector 10 in))) ; => #u8(1 2 255)
(delete-file "bytes.bin")
```
