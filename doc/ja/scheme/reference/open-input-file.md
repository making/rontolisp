# open-input-file

`(open-input-file string)`

`string` という名前のファイルを開き、それを読むテキスト入力ポートを返します。開けないファイルは、`file-error?` が `#t` を返すエラーを raise します。使い終えたら `close-port` で閉じてください。

```scheme
(with-output-to-file "hello.txt" (lambda () (display "hello")))
(define p (open-input-file "hello.txt"))
(read-line p) ; => "hello"
(close-port p)
(delete-file "hello.txt")
```
