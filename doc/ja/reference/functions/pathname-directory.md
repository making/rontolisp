# pathname-directory

`(pathname-directory pathname)`

パス名のディレクトリ部分を、Common Lisp のリスト形式で返します。`:absolute` または
`:relative` に続いて、ディレクトリ階層ごとに 1 つの文字列が並びます。ディレクトリ部分を
持たないパス名は `nil` を返します。

rontolisp のパス名はパス名文字列そのものなので、これは純粋な文字列処理です。ファイル
システムは一切読まず、存在しないパスでも同じ結果を返します。[`directory`](directory.md)
と組み合わせて使います。走査処理は、渡された各エントリをどう扱うかをこの関数で判断します。

```lisp
(pathname-directory "/usr/share/zoneinfo/Asia/Tokyo")   ; => (:ABSOLUTE "usr" "share" "zoneinfo" "Asia")
```

`(pathname-directory "a/b/c.txt")` は `(:RELATIVE "a" "b")`、
`(pathname-directory "c.txt")` は `NIL`、`(pathname-directory "/")` は
`(:ABSOLUTE)` です。

## バックエンドサポート

4 バックエンドすべてです。どのバックエンドにもあるプリミティブの上に、rontolisp ソースで
1 つだけ定義されています。
