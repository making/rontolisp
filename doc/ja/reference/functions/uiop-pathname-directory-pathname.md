# uiop:pathname-directory-pathname

`(uiop:pathname-directory-pathname pathname)`

パス名のディレクトリをパス名として返します -- 名前と型を落とし、最後の `/` までを
残します。[`uiop:subpathname`](uiop-subpathname.md) がその下へマージする対象です。

```lisp
(uiop:pathname-directory-pathname #P"/a/b/c.txt")   ; => #P"/a/b/"
```

## バックエンドサポート

4 つのバックエンドすべてで動作します (Lisp ソース、`uiop-pathname.lisp`)。
