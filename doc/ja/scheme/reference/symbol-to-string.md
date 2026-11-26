# symbol->string

`(symbol->string symbol)`

`symbol` の名前を文字列として返します。識別子は大文字小文字を区別するため、大文字小文字はそのまま保たれます。文字列は新しいコピーなので、書き換えてもシンボルの名前は変わりません。

```scheme
(symbol->string 'flying-fish) ; => "flying-fish"
(symbol->string 'ABC) ; => "ABC"
```
