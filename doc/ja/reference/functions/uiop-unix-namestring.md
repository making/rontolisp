# uiop:unix-namestring

`(uiop:unix-namestring pathname)`

パス名の Unix 形式の名前文字列です。rontolisp の名前文字列はすでに Unix の綴り
なので、これは UIOP の許容度を持つ [`namestring`](namestring.md) です:
`nil` と文字列はそのまま通ります。

```lisp
(uiop:unix-namestring #P"/a/b.c")   ; => "/a/b.c"
```

## バックエンドサポート

4 つのバックエンドすべてで動作します (Lisp ソース、`uiop-pathname.lisp`)。
