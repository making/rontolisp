# string-upcase

`(string-upcase string-designator)`

すべての小文字を大文字に変換した新しい文字列を返します。元の文字列は変更されません。引数は文字列指定子（string designator）であるため、シンボルやキーワードも受け付けます。その名前が使われ、キーワードの先頭のコロンは取り除かれるので、`(string-upcase :foo)` は `"FOO"` を返します。大文字小文字変換は全 Unicode 対応で、すべてのバックエンドで同一です。各文字を `char-upcase` で変換するため、`(string-upcase "éλω")` は `"ÉΛΩ"` を返します。文字単位の変換であるため結果の長さは常に引数と同じで、複数文字へ展開する特別な変換は行いません（`(string-upcase "straße")` は `"STRASSE"` ではなく `"STRAßE"` を返します）。

```lisp
(string-upcase "abc") ; => "ABC"
```
