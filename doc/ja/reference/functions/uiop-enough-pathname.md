# uiop:enough-pathname

`(uiop:enough-pathname maybe-subpath base-pathname)`

[`uiop:subpathp`](uiop-subpathp.md) の残りがあればそれ、なければパス名自身 --
ベースを踏まえてなおファイルを名指す最短の綴りです。rove はソース位置の表示を
これでキーにしています。

```lisp
(uiop:enough-pathname #P"/tmp/a/b.txt" #P"/tmp/")   ; => #P"a/b.txt"
```

```lisp
(uiop:enough-pathname #P"/x/a.txt" #P"/tmp/")   ; => #P"/x/a.txt"
```

`uiop:with-enough-pathname` / `uiop:call-with-enough-pathname` は、
`*default-pathname-defaults*` をベースに束縛したうえでこの値とともに本体を実行します
([uiop/pathname](../uiop/pathname.md))。

## バックエンドサポート

4 つのバックエンドすべてで動作します (Lisp ソース、`uiop-pathname.lisp`)。
