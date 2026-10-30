# load-logical-pathname-translations

`(load-logical-pathname-translations host)`

ホストが [`logical-pathname-translations`](logical-pathname-translations.md) で定義済みなら
`nil` を返します。探索する変換ファイルは無いため、未定義のホストは `file-error` を
送出します。

```lisp
(setf (logical-pathname-translations "APP") '(("**;*.*.*" "/opt/app/**/*.*")))
(load-logical-pathname-translations "APP")
; => NIL
```

## バックエンドサポート

4 バックエンドすべてです。どのバックエンドにもあるプリミティブの上に、rontolisp ソースで
1 つだけ定義されています。
