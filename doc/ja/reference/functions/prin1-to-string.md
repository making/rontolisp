# prin1-to-string

`(prin1-to-string object)`

`prin1` が `object` に対して出力するテキストを文字列として返します。これは読み戻し可能な形式で、文字列は前後のクォートを保持し（文字列中の `"` と `\` にはそれぞれ `\` が前置されます）、文字は `#\` 構文を用います。何も出力されず、レンダリング結果がキャプチャされて返されるため、`(read-from-string (prin1-to-string s))` は `s` を返します。

```lisp
(prin1-to-string "abc") ; => "\"abc\""
```
