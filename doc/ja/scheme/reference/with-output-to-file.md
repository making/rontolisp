# with-output-to-file

`(with-output-to-file string thunk)`

`string` という名前のファイルを `open-output-file` と同じように作り、`thunk` の実行中はそれを現在の出力ポートにして、`thunk` の返したものを返します。脱出や raise されたオブジェクトを含め、`thunk` をどう抜けても、元の現在の出力ポートが戻り、ファイルは閉じられます（その場合 Gauche はファイルを開いたままにします）。

```scheme
(with-output-to-file "out.txt" (lambda () (display "hi")))
(call-with-input-file "out.txt" read-line) ; => "hi"
(delete-file "out.txt")
```
