# pathname-directory

`(pathname-directory pathname)`

パス名のディレクトリ部分を、Common Lisp のリスト形式で返します。`:absolute` または
`:relative` に続いて、ディレクトリ階層ごとに 1 つの構成要素が並びます。ディレクトリ部分を
持たないパス名は `nil` を返します。

これは [`make-pathname`](make-pathname.md) が組み立てるものの構成要素ごとの**逆**なので、
特別な階層は与えたときのキーワードのまま返ります。`..` は `:up`、`*` は `:wild`、
`**` は `:wild-inferiors` です。

引数はパス名と名前文字列のどちらでも受け付け、分割は名前文字列上の純粋な文字列処理です。ファイル
システムは一切読まず、存在しないパスでも同じ結果を返します。[`directory`](directory.md)
と組み合わせて使います。走査処理は、渡された各エントリをどう扱うかをこの関数で判断します。

```lisp
(pathname-directory "/usr/share/zoneinfo/Asia/Tokyo")   ; => (:ABSOLUTE "usr" "share" "zoneinfo" "Asia")
```

`(pathname-directory "a/b/c.txt")` は `(:RELATIVE "a" "b")`、
`(pathname-directory "c.txt")` は `NIL`、`(pathname-directory "/")` は
`(:ABSOLUTE)`、`(pathname-directory "/a/**/x.lisp")` は
`(:ABSOLUTE "a" :WILD-INFERIORS)` です。

## バックエンドサポート

4 バックエンドすべてです。どのバックエンドにもあるプリミティブの上に、rontolisp ソースで
1 つだけ定義されています。
