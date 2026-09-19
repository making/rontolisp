# with-input-from-file

`(with-input-from-file string thunk)`

`string` という名前のファイルを `open-input-file` と同じように開き、`thunk` の実行中はそれを現在の入力ポートにして、`thunk` の返したものを返します。脱出や raise されたオブジェクトを含め、`thunk` をどう抜けても、元の現在の入力ポートが戻り、ファイルは閉じられます（その場合 Gauche はファイルを開いたままにします）。

```scheme
(with-output-to-file "hello.txt" (lambda () (display "hello")))
(with-input-from-file "hello.txt" read-line) ; => "hello"
(delete-file "hello.txt")
```
