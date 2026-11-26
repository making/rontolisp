# call-with-input-file

`(call-with-input-file string proc)`

`string` という名前のファイルを `open-input-file` と同じように開き、そのポートを引数に `proc` を呼び、`proc` が戻ったらポートを閉じて、`proc` の返したものを返します。`proc` が戻らなければ、ポートは開いたままです。

```scheme
(with-output-to-file "hello.txt" (lambda () (display "hello")))
(call-with-input-file "hello.txt" read-line) ; => "hello"
(delete-file "hello.txt")
```
