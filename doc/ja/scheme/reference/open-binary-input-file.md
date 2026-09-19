# open-binary-input-file

`(open-binary-input-file string)`

`string` という名前のファイルを開き、そのバイトを読むバイナリ入力ポートを返します。開けないファイルは、`file-error?` が `#t` を返すエラーを raise します。

```scheme
(with-output-to-file "hello.txt" (lambda () (display "hello")))
(define p (open-binary-input-file "hello.txt"))
(list (read-u8 p) (read-bytevector 2 p)) ; => (104 #u8(101 108))
(close-port p)
(delete-file "hello.txt")
```
