# uiop:split-name-type

`(uiop:split-name-type filename)`

ディレクトリ成分のないファイル名の NAME と TYPE の 2 値です: 最後のドットで
分けられますが、先頭だけのドットは名前に属します (そのとき型は
`uiop:*unspecific-pathname-type*`、すなわち `nil` です)。

```lisp
(multiple-value-list (uiop:split-name-type "foo.lisp"))   ; => ("foo" "lisp")
```

```lisp
(multiple-value-list (uiop:split-name-type ".hidden"))   ; => (".hidden" NIL)
```

## バックエンドサポート

4 つのバックエンドすべてで動作します (Lisp ソース、`uiop-pathname.lisp`)。
