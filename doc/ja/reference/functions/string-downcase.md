# string-downcase

`(string-downcase string-designator)`

すべての大文字を小文字に変換した新しい文字列を返します。元の文字列は変更されません。引数は文字列指定子（string designator）であるため、シンボルやキーワードも受け付けます。その名前が使われ、キーワードの先頭のコロンは取り除かれるので、`(string-downcase :FOO)` は `"foo"` を返します。WASM バックエンドでは大文字小文字変換は ASCII のみであるため、影響を受けるのは `A`-`Z` の文字のみで、非 ASCII 文字はそのまま通過します。

```lisp
(string-downcase "ABC") ; => "abc"
```
