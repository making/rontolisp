# logical-pathname

`(logical-pathname pathspec)`

[`logical-pathname-translations`](logical-pathname-translations.md) で定義したホストを持つ
名前文字列のパス名を返します。それ以外の引数は、Common Lisp の要求どおり `type-error` を
送出します。`SYS` を含め、あらかじめ定義されたホストはありません。

```lisp
(setf (logical-pathname-translations "APP") '(("**;*.*.*" "/opt/app/**/*.*")))
(namestring (logical-pathname "APP:SRC;FOO.LISP"))
; => "APP:SRC;FOO.LISP"
```

返り値は名前文字列を持つ通常のパス名の値で、論理パス名という別の型はありません。
物理パス名への変換は [`translate-logical-pathname`](translate-logical-pathname.md) が
行います。

## バックエンドサポート

4 バックエンドすべてです。どのバックエンドにもあるプリミティブの上に、rontolisp ソースで
1 つだけ定義されています。
