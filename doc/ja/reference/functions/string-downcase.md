# string-downcase

`(string-downcase string-designator)`

すべての大文字を小文字に変換した新しい文字列を返します。元の文字列は変更されません。引数は [文字列指定子](string.md) であるため、シンボル・キーワード・文字も受け付けます。シンボルはその名前が使われ、キーワードの先頭のコロンは取り除かれるので、`(string-downcase :FOO)` は `"foo"`、`(string-downcase #\A)` は `"a"` を返します。この 3 種類以外を渡すとエラーになります。大文字小文字変換は全 Unicode 対応で、すべてのバックエンドで同一です。各文字を `char-downcase` で変換するため、`(string-downcase "ÉΛΩ")` は `"éλω"` を返します。文字単位の変換であるため結果の長さは常に引数と同じで、文脈依存の規則（ギリシャ語の語末シグマなど）も適用されません。

```lisp
(string-downcase "ABC") ; => "abc"
```
