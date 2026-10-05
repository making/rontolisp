# string-capitalize

`(string-capitalize string-designator &key (start 0) end)`

各単語の最初の文字を大文字に、残りの文字を小文字にした新しい文字列を返します。ここで単語とは、他の文字で区切られた英数字の連なりを指します。元の文字列は変更されません。引数は [文字列指定子](string.md) であるため、シンボル・キーワード・文字も受け付けます。シンボルはその名前が使われ、キーワードの先頭のコロンは取り除かれるので、`(string-capitalize :foo-bar)` は `"Foo-Bar"`、`(string-capitalize nil)` は `"Nil"` を返します。この 3 種類以外を渡すとエラーになります。他の大文字小文字変換演算子と同様に変換は全 Unicode 対応ですべてのバックエンドで同一であり、単語を構成するのは任意の Unicode 文字・数字です。したがって `(string-capitalize "élan vital")` は `"Élan Vital"` を返します。

```lisp
(string-capitalize "hello world") ; => "Hello World"
```

`:start` / `:end` は変換する範囲を指定します。範囲外の文字はそのまま残り、`:end` が nil なら文字列の末尾までです。直前の文字にかかわらず、`:start` の位置から単語が始まります。文字列の範囲外 (`:start` が 0 未満、`:end` が長さを超える、`:start` が `:end` より後) と、整数でない境界 (nil の `:start` を含む) は、どのバックエンドでも [`subseq`](subseq.md) と同じ `type-error` です。

```lisp
(string-capitalize "one two" :start 4) ; => "one Two"
```
