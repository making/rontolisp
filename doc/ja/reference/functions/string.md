# string

`(string x)`

*文字列指定子 (string designator)* を文字列に変換します。文字列はそのまま返され、シンボルはその [`symbol-name`](symbol-name.md) に(キーワードの先頭の `:` と gensym の `#:` はパッケージマーカーなので取り除かれます)、文字は 1 文字の文字列になります。`t` と `nil` はシンボルと同様に変換されます (`"T"` / `"NIL"`)。シンボルは Common Lisp 同様大文字化されて読まれるため `(string 'foo)` は `"FOO"`、`(string 'car)` は `"CAR"` です。

指定子でない引数は、どのバックエンドでもエラーを通知します。`string` は文字列指定子を受け取るすべての位置 ([`string-trim`](string-trim.md) 系のトリム対象、大文字小文字変換、[`string=`](string-eq.md) と順序比較述語) が経由する唯一の変換なので、ここで黙って受理すると型エラーになるべきものがそれらの位置で誤った答えに化けてしまいます。

```lisp
(string 'foo) ; => "FOO"
```

```lisp
(string #\a) ; => "a"
```

```lisp
(string "already") ; => "already"
```
