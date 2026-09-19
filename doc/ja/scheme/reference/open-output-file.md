# open-output-file

`(open-output-file string)`

`string` という名前のファイルを作り（既にあれば空にし）、そこに書くテキスト出力ポートを返します。作れないファイルは、`file-error?` が `#t` を返すエラーを raise します。書いたものはポートを閉じるまでバッファに残ることがあるので、`close-port` で閉じてください: インタプリタと JVM では、プログラムの終了時に開いたままのポートにバッファされた出力は失われます。

```scheme
(define p (open-output-file "out.txt"))
(write '(1 "two") p)
(close-port p)
(call-with-input-file "out.txt" read) ; => (1 "two")
(delete-file "out.txt")
```
