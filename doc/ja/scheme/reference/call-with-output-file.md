# call-with-output-file

`(call-with-output-file string proc)`

`string` という名前のファイルを `open-output-file` と同じように作り、そのポートを引数に `proc` を呼び、`proc` が戻ったらポートを閉じて、`proc` の返したものを返します。`proc` が戻らなければ、ポートは開いたままです。

```scheme
(call-with-output-file "out.txt" (lambda (p) (write '(1 2) p)))
(call-with-input-file "out.txt" read) ; => (1 2)
(delete-file "out.txt")
```
