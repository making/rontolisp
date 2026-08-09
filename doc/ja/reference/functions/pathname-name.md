# pathname-name

`(pathname-name pathname)`

パス名のファイル名部分を、型 (拡張子) を除いて返します。最後の `/` より後ろで、
かつ**最後の**ドットより前の部分です。位置 0 のドットは型の区切りではなく名前の
一部として扱われ、ファイルを指さないパス名 (`/` で終わるもの) は `nil` を返します。

引数はパス名と名前文字列のどちらでも受け付け、分割は名前文字列上の純粋な文字列処理です。ファイル
システムは一切読まず、存在しないパスでも同じ結果を返します。分割の規則は、もう半分を
返す [`pathname-type`](pathname-type.md) と、`:defaults` の補完に使う
[`make-pathname`](make-pathname.md) とまったく同じなので、3 つが食い違うことはありません。

```lisp
(pathname-name "db/migrations/20260101.up.sql")   ; => "20260101.up"
```

`(pathname-name "d/a")` は `"a"`、`(pathname-name "d/.a")` は `".a"`、
`(pathname-name "d/a.b.c")` は `"a.b"`、`(pathname-name "d/")` は `NIL` です。

## バックエンドサポート

4 バックエンドすべてです。どのバックエンドにもあるプリミティブの上に、rontolisp ソースで
1 つだけ定義されています。
