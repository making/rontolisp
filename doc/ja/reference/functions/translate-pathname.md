# translate-pathname

`(translate-pathname source from-wildcard to-wildcard &key)`

`source` を `from-wildcard` と照合し、ワイルドカードが捕捉した部分を左から順に
`to-wildcard` のワイルドカードへ差し込みます。ワイルドカードはパス名系の他の演算子と
同じ `*` (任意の長さの並び)、`?` (1 文字)、`**/` (0 個以上のディレクトリ階層) です。
`from-wildcard` に一致しない `source` は Common Lisp と同様にエラーになります。

```lisp
(list (namestring (translate-pathname "src/foo.lisp" "src/*.lisp" "build/*.fasl"))
      (namestring (translate-pathname "a/b.c" "*/*.*" "x/*-y.*")))
; => ("build/foo.fasl" "x/a-y.b")
```

`**/` は区切りの `/` まで含めて**1 つの**ワイルドカードです。消費したディレクトリ
階層の並びをまるごと捕捉し、`to-wildcard` 側の `**/` はそれをそのまま書き戻します。
0 階層にも一致するので、間にディレクトリのない `source` も変換できます。

```lisp
(list (namestring (translate-pathname "/a/b/d/c.lisp" "/a/**/*.lisp" "/x/**/*.fasl"))
      (namestring (translate-pathname "/a/c.lisp" "/a/**/*.lisp" "/x/**/*.fasl")))
; => ("/x/b/d/c.fasl" "/x/c.fasl")
```

制限: 照合は**フラットな**名前文字列の上で行われ、捕捉した値は構成要素ごとではなく
**位置順**に差し込まれます。そのため、構造化されたパス名を持つ処理系ならディレクトリ
境界で止まる場面でも素の `*` が `/` をまたぐことがあり、`to-wildcard` の
ワイルドカードが `from-wildcard` より少ない場合は対応する構成要素ではなく先頭から
順に消費されます。

## バックエンドサポート

4 バックエンドすべてです。どのバックエンドにもあるプリミティブの上に、rontolisp ソースで
1 つだけ定義されています。
