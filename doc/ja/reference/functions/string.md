# string

`(string x)`

*文字列指定子 (string designator)* を文字列に変換します。文字列はそのまま返され、シンボルはその [`symbol-name`](symbol-name.md) に(キーワードの先頭の `:` と gensym の `#:` はパッケージマーカーなので取り除かれます)、文字は 1 文字の文字列になります。`t` と `nil` はシンボルと同様に変換されます (`"t"` / `"nil"`)。ユーザーシンボルは Common Lisp 同様大文字化されて読まれるため `(string 'foo)` は `"FOO"` です。標準シンボルは正規の小文字の綴りを保ちます(`(string 'car)` は `"car"`)。

コンパイル系バックエンド (JVM/WASM) では `string` は `princ-to-string` の仕組みを共有するため、指定子でない引数はエラーを通知せず、その表示テキストを返します (インタプリタはエラーを通知します)。

```lisp
(string 'foo) ; => "foo"
```

```lisp
(string #\a) ; => "a"
```

```lisp
(string "already") ; => "already"
```
