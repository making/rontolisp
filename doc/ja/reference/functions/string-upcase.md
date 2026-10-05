# string-upcase

`(string-upcase string-designator &key (start 0) end)`

すべての小文字を大文字に変換した新しい文字列を返します。元の文字列は変更されません。引数は [文字列指定子](string.md) であるため、シンボル・キーワード・文字も受け付けます。シンボルはその名前が使われ、キーワードの先頭のコロンは取り除かれるので、`(string-upcase :foo)` は `"FOO"`、`(string-upcase #\a)` は `"A"` を返します。この 3 種類以外を渡すとエラーになります。大文字小文字変換は全 Unicode 対応で、すべてのバックエンドで同一です。各文字を `char-upcase` で変換するため、`(string-upcase "éλω")` は `"ÉΛΩ"` を返します。文字単位の変換であるため結果の長さは常に引数と同じで、複数文字へ展開する特別な変換は行いません（`(string-upcase "straße")` は `"STRASSE"` ではなく `"STRAßE"` を返します）。

```lisp
(string-upcase "abc") ; => "ABC"
```

`:start` / `:end` は変換する範囲を指定します。範囲外の文字はそのまま残り、`:end` が nil なら文字列の末尾までです。文字列の範囲外 (`:start` が 0 未満、`:end` が長さを超える、`:start` が `:end` より後) と、`nil` 以外の整数でない境界は、どのバックエンドでも [`subseq`](subseq.md) と同じ `type-error` です。

```lisp
(string-upcase "hello" :start 1 :end 3) ; => "hELlo"
```
