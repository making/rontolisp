# translate-pathname

`(translate-pathname source from-wildcard to-wildcard &key)`

`source` を `from-wildcard` と照合し、ワイルドカードが捕捉した部分を
`to-wildcard` の対応する構成要素のワイルドカードへ差し込みます。ワイルドカードはパス名系の他の演算子と
同じ `*` (任意の長さの並び)、`?` (1 文字)、`**/` (0 個以上のディレクトリ階層) です。
`from-wildcard` に一致しない `source` は Common Lisp と同様にエラーになります。

```lisp
(list (namestring (translate-pathname "src/foo.lisp" "src/*.lisp" "build/*.fasl"))
      (namestring (translate-pathname "a/b.c" "*/*.*" "x/*-y.*")))
; => ("build/foo.fasl" "x/b-y.c")
```

`**/` は区切りの `/` まで含めて**1 つの**ワイルドカードです。消費したディレクトリ
階層の並びをまるごと捕捉し、`to-wildcard` 側の `**/` はそれをそのまま書き戻します。
0 階層にも一致するので、間にディレクトリのない `source` も変換できます。

```lisp
(list (namestring (translate-pathname "/a/b/d/c.lisp" "/a/**/*.lisp" "/x/**/*.fasl"))
      (namestring (translate-pathname "/a/c.lisp" "/a/**/*.lisp" "/x/**/*.fasl")))
; => ("/x/b/d/c.fasl" "/x/c.fasl")
```

制限: 照合も差し込みも構成要素ごとに行われます(素の `*` や `?` はディレクトリ
境界をまたがず、`**/` はディレクトリ階層の並びをまるごと捕捉します)。ただし、
構造化された処理系が立てる診断の一部は立てません。`to-wildcard` のワイルドカードに
渡す捕捉が残っていなければ空文字列を差し込み(`from-wildcard` のワイルドカードが
足りないというエラーにはならない)、1 つの構成要素の中に並ぶワイルドカードは
それぞれが 1 つの捕捉を消費し、対応の崩れた `**` は検査しません。

## バックエンドサポート

4 バックエンドすべてです。どのバックエンドにもあるプリミティブの上に、rontolisp ソースで
1 つだけ定義されています。
