# string-upcase

`(string-upcase string-designator)`

すべての小文字を大文字に変換した新しい文字列を返します。元の文字列は変更されません。引数は文字列指定子（string designator）であるため、シンボルやキーワードも受け付けます。その名前が使われ、キーワードの先頭のコロンは取り除かれるので、`(string-upcase :foo)` は `"FOO"` を返します。WASM バックエンドでは大文字小文字変換は ASCII のみであるため、影響を受けるのは `a`-`z` の文字のみで、非 ASCII 文字はそのまま通過します。

```lisp
(string-upcase "abc") ; => "ABC"
```
