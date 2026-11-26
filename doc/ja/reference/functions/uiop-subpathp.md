# uiop:subpathp

`(uiop:subpathp maybe-subpath base-pathname)`

`maybe-subpath` が `base-pathname` の下にあるとき、ベースにマージし直すと
`maybe-subpath` に戻る相対パス名を返します。そうでなければ `nil` です。両引数とも
パス名オブジェクトで、両方とも絶対、ベースはディレクトリ形式でなければなりません。

```lisp
(uiop:subpathp #P"/tmp/foo/bar.txt" #P"/tmp/")   ; => #P"foo/bar.txt"
```

```lisp
(uiop:subpathp #P"/other/x" #P"/tmp/")   ; => NIL
```

## バックエンドサポート

4 つのバックエンドすべてで動作します (Lisp ソース、`uiop-pathname.lisp`)。
