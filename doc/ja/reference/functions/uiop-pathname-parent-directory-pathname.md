# uiop:pathname-parent-directory-pathname

`(uiop:pathname-parent-directory-pathname pathname)`

パス名のディレクトリを 1 段上へ: `/foo/bar/baz/file.type` は `#P"/foo/bar/"` を
返します。ルートの親はルートで、1 段だけの相対ディレクトリの親は空のパス名です。

```lisp
(uiop:pathname-parent-directory-pathname #P"/a/b/c.txt")   ; => #P"/a/"
```

## バックエンドサポート

4 つのバックエンドすべてで動作します (Lisp ソース、`uiop-pathname.lisp`)。
