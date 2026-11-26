# uiop:file-pathname-p

`(uiop:file-pathname-p pathname)`

名前または型の成分があるとき -- 名前文字列がディレクトリではなくファイルを名指す
とき -- パース済みのパス名を、そうでなければ `nil` を返します。ファイルの存在は
確認**しません** (それは `uiop:file-exists-p` です)。

```lisp
(uiop:file-pathname-p "/a/b")   ; => #P"/a/b"
```

```lisp
(uiop:file-pathname-p "/a/b/")   ; => NIL
```

## バックエンドサポート

4 つのバックエンドすべてで動作します (Lisp ソース、`uiop-pathname.lisp`)。
