# open-output-file

`(open-output-file string)`

`string` という名前のファイルを作り（既にあれば空にし）、そこに書くテキスト出力ポートを返します。作れないファイルは、`file-error?` が `#t` を返すエラーを raise します。書いたものは、`close-port` でポートを閉じるかプログラムが終わるまでバッファに残ることがあります。

```scheme
(define p (open-output-file "out.txt"))
(write '(1 "two") p)
(close-port p)
(call-with-input-file "out.txt" read) ; => (1 "two")
(delete-file "out.txt")
```
