# uiop:directory-pathname-p

`(uiop:directory-pathname-p pathname)`

パス名がディレクトリ形式 -- ワイルドでなく、名前も型もない、すなわち空か `/` で
終わる -- のとき `t` です。ディレクトリの存在は確認**しません**
(それは `uiop:directory-exists-p` です)。

```lisp
(uiop:directory-pathname-p "/a/b/")   ; => T
```

```lisp
(uiop:directory-pathname-p "/a/b")   ; => NIL
```

## バックエンドサポート

4 つのバックエンドすべてで動作します (Lisp ソース、`uiop-pathname.lisp`)。
