# pathname-type

`(pathname-type pathname)`

パス名の型 (拡張子) 部分を、ドットを除いて返します。ファイル名部分の**最後の**ドットより
後ろの部分で、型がなければ `nil` です。位置 0 のドットは型を区切らないので、ドット
ファイルは名前だけを持ち型は持ちません。

引数はパス名と名前文字列のどちらでも受け付け、分割は名前文字列上の純粋な文字列処理です。ファイル
システムは一切読まず、存在しないパスでも同じ結果を返します。
[`pathname-name`](pathname-name.md) が取るのと同じ分割の、もう半分にあたります。

```lisp
(pathname-type "db/migrations/20260101.up.sql")   ; => "sql"
```

`(pathname-type "d/a")` は `NIL`、`(pathname-type "d/.a")` は `NIL`、
`(pathname-type "d/a.b.c")` は `"c"` です。

## バックエンドサポート

4 バックエンドすべてです。どのバックエンドにもあるプリミティブの上に、rontolisp ソースで
1 つだけ定義されています。
