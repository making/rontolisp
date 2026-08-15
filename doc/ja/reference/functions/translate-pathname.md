# translate-pathname

`(translate-pathname source from-wildcard to-wildcard &key)`

`source` を `from-wildcard` と照合し、ワイルドカードが捕捉した部分を左から順に
`to-wildcard` のワイルドカードへ差し込みます。ワイルドカードはパス名系の他の演算子と
同じ `*` (任意の長さの並び) と `?` (1 文字) です。`from-wildcard` に一致しない
`source` は Common Lisp と同様にエラーになります。

```lisp
(list (namestring (translate-pathname "src/foo.lisp" "src/*.lisp" "build/*.fasl"))
      (namestring (translate-pathname "a/b.c" "*/*.*" "x/*-y.*")))
; => ("build/foo.fasl" "x/a-y.b")
```

制限: 照合は**フラットな**名前文字列の上で行われるため、構造化されたパス名を持つ
処理系ならディレクトリ境界で止まる場面でも `*` が `/` をまたぐことがあります。

## バックエンドサポート

4 バックエンドすべてです。どのバックエンドにもあるプリミティブの上に、rontolisp ソースで
1 つだけ定義されています。
