# logical-pathname-translations

`(logical-pathname-translations host)`, `(setf (logical-pathname-translations host) translations)`

論理ホストに記録された変換リストです。`setf` はホストを定義するか (名前の大文字小文字は
区別しません)、そのリストを置き換えます。定義されていないホストを読むと SBCL と同じく
`type-error` を送出します。各変換は `(from-wildcard to-wildcard)` で、`"**;*.*.*"` の
ような論理 from-wildcard と、それを写す物理 to-wildcard の組です。
[`translate-logical-pathname`](translate-logical-pathname.md) が読みます。

```lisp
(setf (logical-pathname-translations "APP") '(("**;*.*.*" "/opt/app/**/*.*")))
(logical-pathname-translations "app")
; => (("**;*.*.*" "/opt/app/**/*.*"))
```

## バックエンドサポート

4 バックエンドすべてです。どのバックエンドにもあるプリミティブの上に、rontolisp ソースで
1 つだけ定義されています。
