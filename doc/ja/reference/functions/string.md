# string

`(string x)`

*文字列指定子 (string designator)* を文字列に変換します。文字列はそのまま返され、シンボルはその名前に、文字は 1 文字の文字列になります。`t` と `nil` はシンボルと同様に変換されます (`"t"` / `"nil"`)。rontolisp のシンボル名は大文字小文字を保持するため、Common Lisp とは異なり結果は大文字化されません。

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
