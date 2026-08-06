# string-capitalize

`(string-capitalize string-designator)`

各単語の最初の文字を大文字に、残りの文字を小文字にした新しい文字列を返します。ここで単語とは、他の文字で区切られた英数字の連なりを指します。元の文字列は変更されません。引数は文字列指定子（string designator）であるため、シンボルやキーワードも受け付けます。その名前が使われ、キーワードの先頭のコロンは取り除かれるので、`(string-capitalize :foo-bar)` は `"Foo-Bar"` を返します。他の大文字小文字変換演算子と同様に変換は全 Unicode 対応ですべてのバックエンドで同一であり、単語を構成するのは任意の Unicode 文字・数字です。したがって `(string-capitalize "élan vital")` は `"Élan Vital"` を返します。

```lisp
(string-capitalize "hello world") ; => "Hello World"
```
